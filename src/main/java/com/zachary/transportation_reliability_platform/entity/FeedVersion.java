package com.zachary.transportation_reliability_platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Getter
@Setter
@NoArgsConstructor
@TableName("feed_versions")
public class FeedVersion {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String sourceUri;
    private String checksum;
    private LocalDate effectiveFrom;
    private OffsetDateTime importedAt;
    private String lifecycleStatus;
}
