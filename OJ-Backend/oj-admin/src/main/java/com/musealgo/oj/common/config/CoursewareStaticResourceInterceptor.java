package com.musealgo.oj.common.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.musealgo.oj.common.utils.SecurityUtils;
import com.musealgo.oj.teaching.entity.Courseware;
import com.musealgo.oj.teaching.service.ClassCoursewareService;
import com.musealgo.oj.teaching.service.CoursewareService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.net.URI;
import java.util.List;
import java.util.regex.Pattern;

/** Enforces the courseware record's privacy policy for every generated static asset. */
@Component
@RequiredArgsConstructor
public class CoursewareStaticResourceInterceptor implements HandlerInterceptor {

    private static final Pattern OUTPUT_DIRECTORY = Pattern.compile("[0-9a-fA-F]{32}");
    private static final String COURSEWARE_PREFIX = "/coursewares/";
    private static final String API_COURSEWARE_PREFIX = "/api/coursewares/";
    private static final Pattern ENCODED_PATH_CONTROL = Pattern.compile("(?i)%(?:2e|2f|5c|25)");

    private final CoursewareService coursewareService;
    private final ClassCoursewareService classCoursewareService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String requestPath = request.getRequestURI().substring(request.getContextPath().length());
        String directory = outputDirectory(requestPath);
        if (directory == null || !hasSafeResourcePath(requestPath)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return false;
        }

        List<Courseware> matches = coursewareService.list(new LambdaQueryWrapper<Courseware>()
                .and(query -> query
                        .like(Courseware::getUrl, COURSEWARE_PREFIX + directory + "/")
                        .or()
                        .like(Courseware::getUrl, API_COURSEWARE_PREFIX + directory + "/")));
        List<Courseware> exactMatches = matches.stream()
                .filter(courseware -> belongsToDirectory(courseware.getUrl(), directory))
                .toList();
        // An ambiguous or unregistered output has no authoritative privacy owner.
        if (exactMatches.size() != 1) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return false;
        }

        Courseware courseware = exactMatches.get(0);
        if (Integer.valueOf(1).equals(courseware.getPrivacy())) {
            return true;
        }

        response.setHeader("Cache-Control", "private, no-store");
        Long userId = SecurityUtils.getUserId();
        if (userId == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        boolean allowed = userId.equals(courseware.getTeacherId())
                || SecurityUtils.isAdmin()
                || classCoursewareService.isVisibleForMember(courseware.getId(), userId);
        if (!allowed) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        return true;
    }

    private static String outputDirectory(String requestPath) {
        String prefix = requestPath.startsWith(API_COURSEWARE_PREFIX)
                ? API_COURSEWARE_PREFIX
                : requestPath.startsWith(COURSEWARE_PREFIX) ? COURSEWARE_PREFIX : null;
        if (prefix == null) return null;

        String remainder = requestPath.substring(prefix.length());
        int separator = remainder.indexOf('/');
        if (separator <= 0) return null;
        String directory = remainder.substring(0, separator);
        return OUTPUT_DIRECTORY.matcher(directory).matches() ? directory : null;
    }

    private static boolean hasSafeResourcePath(String requestPath) {
        if (requestPath.indexOf('\\') >= 0 || ENCODED_PATH_CONTROL.matcher(requestPath).find()) return false;
        String prefix = requestPath.startsWith(API_COURSEWARE_PREFIX)
                ? API_COURSEWARE_PREFIX
                : requestPath.startsWith(COURSEWARE_PREFIX) ? COURSEWARE_PREFIX : null;
        if (prefix == null) return false;
        String remainder = requestPath.substring(prefix.length());
        int separator = remainder.indexOf('/');
        if (separator <= 0 || separator == remainder.length() - 1) return false;
        for (String segment : remainder.substring(separator + 1).split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) return false;
        }
        return true;
    }

    private static boolean belongsToDirectory(String url, String directory) {
        if (url == null) return false;
        try {
            String path = URI.create(url).getPath();
            return path.startsWith(COURSEWARE_PREFIX + directory + "/")
                    || path.startsWith(API_COURSEWARE_PREFIX + directory + "/");
        } catch (IllegalArgumentException invalidUrl) {
            return false;
        }
    }
}
