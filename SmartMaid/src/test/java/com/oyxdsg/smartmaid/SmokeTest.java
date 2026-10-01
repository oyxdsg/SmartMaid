package com.oyxdsg.smartmaid;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 离线测试基建冒烟测试：确认 JUnit 能在本工程跑通（不依赖 Minecraft 运行时）。 */
class SmokeTest {
    @Test
    void smoke() {
        assertEquals(4, 2 + 2);
    }
}
