package com.firefly;

import com.firefly.application.ApplicationTests;

/** 所有回归套件的唯一入口；新增套件在此注册。 */
public final class AllTests {
    public static void main(String[] args) throws Exception {
        TemplateFeatureTests.main(args);
        ApplicationTests.main(args);
    }
}
