package io.github.xiaoaiswitch.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MemoryCommandTest {
    @Test public void nativeScreenshotOriginsDoNotDependOnTheGenericQueryText() {
        assertNull(MemoryCommand.query("记一下"));
        assertTrue(MemoryCommand.isNativeCaptureOrigin("com.miui.voiceassist.query&&three_gesture_up"));
        assertTrue(MemoryCommand.isNativeCaptureOrigin("com.miui.voiceassist.query&&ai_physical_key"));
        assertTrue(MemoryCommand.isNativeCaptureOrigin("com.miui.voiceassist.query&&favorite_auto_sync"));
        assertTrue(MemoryCommand.isNativeCaptureOrigin("os_bot_trigger_favorite"));
        assertFalse(MemoryCommand.isNativeCaptureOrigin("three_gesture_up"));
        assertFalse(MemoryCommand.isNativeCaptureOrigin("com.miui.voiceassist.query&&three_gesture_up_extra"));
        assertFalse(MemoryCommand.isNativeCaptureOrigin(null));
    }
    @Test public void removesTheExplicitRoutePrefixAndOnlyOuterWhitespace() {
        assertEquals("我之前记过什么？", MemoryCommand.query("  /memory   我之前记过什么？  "));
        assertEquals("原厂  记忆", MemoryCommand.query("\t/memory　原厂  记忆　"));
        assertNull(MemoryCommand.query("/memory"));
        assertNull(MemoryCommand.query("/memory　"));
        assertNull(MemoryCommand.query("/memoryx 查一下"));
        assertNull(MemoryCommand.query("/memory: 查一下"));
    }

    @Test public void preservesExplicitNativeWrites() {
        assertEquals("记住我喜欢无糖咖啡", MemoryCommand.query("记住我喜欢无糖咖啡"));
        assertEquals("请记住：我更喜欢深蓝色", MemoryCommand.query("　请记住：我更喜欢深蓝色  "));
        assertEquals("帮我记住：青竹书签", MemoryCommand.query("帮我记住：青竹书签"));
        assertEquals("请帮我 记住青竹", MemoryCommand.query(" 请帮我 记住青竹\n"));
        assertEquals("请把青竹记到我的记忆里", MemoryCommand.query("/memory 请把青竹记到我的记忆里"));
        assertNull(MemoryCommand.query("请记住。"));
        assertNull(MemoryCommand.query("帮我记住："));
    }

    @Test public void preservesExplicitQueriesAndTheNativeManagementRequest() {
        assertEquals("你记得我喜欢什么颜色吗？", MemoryCommand.query("你记得我喜欢什么颜色吗？"));
        assertEquals("你还记得我最喜欢的颜色吗", MemoryCommand.query("你还记得我最喜欢的颜色吗"));
        assertEquals("查一下我的记忆：青竹", MemoryCommand.query("查一下我的记忆：青竹"));
        assertEquals("请帮我查看我的记忆", MemoryCommand.query("请帮我查看我的记忆"));
        assertEquals("用我的记忆回答：我喜欢什么？", MemoryCommand.query("用我的记忆回答：我喜欢什么？"));
        assertEquals("打开超级小爱记忆", MemoryCommand.query("打开超级小爱记忆"));
    }

    @Test public void keepsOrdinaryChatAndQuotedExamplesWithTheSelectedModel() {
        assertNull(MemoryCommand.query(null));
        assertNull(MemoryCommand.query("  　"));
        assertNull(MemoryCommand.query("解释什么是记忆"));
        assertNull(MemoryCommand.query("“请记住我喜欢咖啡”是什么意思"));
        assertNull(MemoryCommand.query("请解释“帮我记住”的语法"));
        assertNull(MemoryCommand.query("写一个人说：帮我记住你的名字"));
        assertNull(MemoryCommand.query("记住是什么意思"));
        assertNull(MemoryCommand.query("我之前记过这件事。"));
        assertNull(MemoryCommand.query("删除我的全部记忆"));
    }
}
