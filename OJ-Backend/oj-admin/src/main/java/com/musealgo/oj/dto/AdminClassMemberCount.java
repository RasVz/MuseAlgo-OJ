package com.musealgo.oj.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Membership count projection limited to one page of class IDs. */
@Getter
@Setter
@NoArgsConstructor
public class AdminClassMemberCount {

    private Long classId;
    private Long studentCount;
}
