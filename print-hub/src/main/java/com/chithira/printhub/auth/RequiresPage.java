package com.chithira.printhub.auth;

import com.chithira.printhub.common.PageKey;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Put on a controller class or method: the user's roles must allow this page.
 * GET needs "view"; any other method (POST…) needs "edit", unless {@code edit} forces it.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPage {
    PageKey value();
    boolean edit() default false;
}
