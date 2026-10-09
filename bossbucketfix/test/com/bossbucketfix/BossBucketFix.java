package com.bossbucketfix;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 测试用替身：只提供 BucketGuard 需要的那两个静态成员，不打进 jar。 */
public final class BossBucketFix {

    public static final String MOD_ID = "bossbucketfix";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private BossBucketFix() {
    }
}
