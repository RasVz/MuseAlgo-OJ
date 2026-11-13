package com.musealgo.oj.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Typed, read-only row returned by the administrator class directory. */
@Getter
@Setter
@NoArgsConstructor
public class AdminClassSummary {

    private Long id;
    private String name;
    private String description;
    private String school;
    private Long teacherId;
    private String teacherName;
    private Long studentCount;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;
}
