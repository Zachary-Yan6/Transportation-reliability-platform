package com.zachary.transportation_reliability_platform.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * Registers MyBatis mapper interfaces for the running application.
 *
 * Keeping this separate from the Spring Boot entry point prevents MVC slice
 * tests from creating database mappers when they intentionally load no
 * SqlSessionFactory.
 */
@Configuration(proxyBeanMethods = false)
@MapperScan("com.zachary.transportation_reliability_platform.mapper")
public class MyBatisMapperConfiguration {
}
