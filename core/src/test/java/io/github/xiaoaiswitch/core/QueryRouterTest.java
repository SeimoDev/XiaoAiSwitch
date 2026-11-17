package io.github.xiaoaiswitch.core;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class QueryRouterTest {
    @Test public void visionAttachmentsTakeTheirOwnRouteWithoutOverridingExplicitCommandsOrDrawing() {
        assertEquals(new QueryRouter.Selection("vision", "图里有什么"), QueryRouter.select("图里有什么", "chat", false, true));
        assertEquals(new QueryRouter.Selection("image", "把颜色改成蓝色"), QueryRouter.select("把颜色改成蓝色", "chat", true, true));
        assertEquals(new QueryRouter.Selection("image", "编辑"), QueryRouter.select("/draw 编辑", "chat", false, true));
        assertEquals(new QueryRouter.Selection("chat", "这是什么"), QueryRouter.select("/chat 这是什么", "chat", false, true));
        assertEquals(new QueryRouter.Selection("vision", "这是什么"), QueryRouter.select("/vision 这是什么", "chat", false, false));
    }

    @Test public void explicitRouteOverridesDefaultWithoutLeakingPrefixToModel() {
        assertEquals(new QueryRouter.Selection("image", "画猫"), QueryRouter.select("/draw 画猫", "chat", false));
        assertEquals(new QueryRouter.Selection("voice", "你好"), QueryRouter.select("/voice 你好", "chat", false));
        assertEquals(new QueryRouter.Selection("control", "打开设置"), QueryRouter.select("/control 打开设置", "chat", false));
        assertEquals(new QueryRouter.Selection("chat", "你好"), QueryRouter.select("/chat 你好", "image", true));
    }

    @Test public void nativeDrawingContextAndDefaultModeHaveDeterministicRouting() {
        assertEquals(new QueryRouter.Selection("image", "猫"), QueryRouter.select("猫", "chat", true));
        assertEquals(new QueryRouter.Selection("image", "猫"), QueryRouter.select("猫", "image", false));
        assertEquals(new QueryRouter.Selection("chat", "/unknown 猫"), QueryRouter.select("/unknown 猫", "chat", false));
        assertThrows(IllegalArgumentException.class, () -> QueryRouter.select("猫", "bogus", false));
    }
}
