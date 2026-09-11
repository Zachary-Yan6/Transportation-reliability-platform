package com.zachary.transportation_reliability_platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@TableName("service_calendar_dates")
public class ServiceCalendarDate {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long feedVersionId;
    private String externalServiceId;
    private LocalDate serviceDate;

    // 1 = added service, 2 = removed service
    private Short exceptionType;
}