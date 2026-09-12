package com.zachary.transportation_reliability_platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/** Persistent application account. Passwords are stored only as BCrypt hashes. */
@Getter
@Setter
@TableName("app_users")
public class AppUser {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String email;
    private String passwordHash;
    private String role;
    private Boolean enabled;
    private OffsetDateTime createdAt;
}
