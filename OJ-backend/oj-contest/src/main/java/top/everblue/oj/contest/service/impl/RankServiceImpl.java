package top.everblue.oj.contest.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.everblue.oj.api.user.UserQuery;
import top.everblue.oj.api.user.UserRatingCommand;
import top.everblue.oj.contest.entity.vo.RankVO;
import top.everblue.oj.contest.service.RankService;
import java.util.*;

/** 全站总榜读取数据库当前评分，结算后立即生效，缓存丢失或重启不影响名次。 */
@Service
@RequiredArgsConstructor
public class RankServiceImpl implements RankService {
    private final UserQuery users;
    private final UserRatingCommand ratings;
    @Override public List<RankVO> getTopRank(int topN) {
        var entries=ratings.findTopRatings(Math.min(100,Math.max(0,topN)));
        var display=users.findDisplayInfoBatch(entries.stream().map(UserRatingCommand.RatingEntry::userId).toList());
        List<RankVO> result=new ArrayList<>();
        for(int i=0;i<entries.size();i++) {
            var entry=entries.get(i);var user=display.get(entry.userId());if(user==null)continue;
            var row=new RankVO();row.setRank(i+1);row.setId(user.id());row.setUsername(user.username());row.setAvatar(user.avatar());
            row.setAcceptedCount(user.acceptedCount());row.setMotto(user.motto());row.setSchool(user.school());row.setRating(entry.rating());result.add(row);
        }
        return result;
    }
    @Override public Long getMyRank(Long userId) { return ratings.findRank(userId); }
}
