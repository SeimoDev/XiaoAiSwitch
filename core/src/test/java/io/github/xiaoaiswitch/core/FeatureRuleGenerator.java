package io.github.xiaoaiswitch.core;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Regenerates reviewed feature fingerprints from the same two signed baseline APKs. No APK material is written out. */
public final class FeatureRuleGenerator {
    private FeatureRuleGenerator() {}

    public static void main(String[] args) throws Exception {
        generate(new File(args[0]), Path.of(args[2], "hook-layout-7.tsv"), symbols(false));
        generate(new File(args[1]), Path.of(args[2], "hook-layout-8.tsv"), symbols(true));
    }

    private static void generate(File apk, Path output, Map<String, String> symbols) throws Exception {
        var rules = FeatureHookDiscovery.rulesFor(List.of(apk), symbols);
        var found = FeatureHookDiscovery.find(List.of(apk), rules);
        for (var symbol : symbols.entrySet()) {
            String value = symbol.getValue().contains("#") ? symbol.getValue().split("#", 2)[1].split(":")[1] : symbol.getValue();
            if (!value.equals(found.get(symbol.getKey()))) throw new IllegalStateException("Non-unique reference: " + symbol.getKey());
        }
        Files.createDirectories(output.getParent());
        Files.writeString(output, rules.stream().map(FeatureHookDiscovery.Rule::line).collect(Collectors.joining("\n", "", "\n")));
        System.out.println("Verified " + found.size() + " feature symbols for " + output.getFileName());
    }

    private static Map<String, String> symbols(boolean modern) {
        var symbols = new HashMap<String, String>();
        symbols.put("tts", modern ? "p31.e1" : "b30.z0");
        symbols.put("ttsSingleton", symbols.get("tts") + "#f:a");
        symbols.put("ttsCallback", modern ? "g21.a" : "s10.b");
        symbols.put("dialogContext", modern ? "com.xiaomi.voiceassist.engine.context.j0" : "com.xiaomi.voiceassist.engine.context.b");
        symbols.put("producer", modern ? "sj1.b" : "vf0.a");
        symbols.put("prefs", modern ? "com.xiaomi.voiceassist.baselibrary.utils.i1" : "m20.z0");
        symbols.put("cache", symbols.get("prefs") + (modern ? "$f0" : "$d0"));
        symbols.put("chainOwner", modern ? "dp1.d3" : "zj0.m1");
        symbols.put("chain", modern ? "ap1.k" : "wj0.j");
        symbols.put("gallery", modern ? "jf1.n1$b" : "fc0.l1$b");
        symbols.put("galleryOpen", symbols.get("gallery") + "#m:g:1");
        symbols.put("glide", modern ? "je1.a" : "gb0.a");
        symbols.put("markdownOperation", modern ? "zg1.jf" : "sd0.od");
        symbols.put("fullFactory", symbols.get("markdownOperation") + (modern ? "#m:i0:1" : "#m:g0:1"));
        symbols.put("floatingFactory", symbols.get("markdownOperation") + (modern ? "#m:k0:1" : "#m:h0:1"));
        symbols.put("fullCard", modern ? "com.xiaomi.voiceassistant.card.k" : "com.xiaomi.voiceassistant.card.i");
        symbols.put("streamedCard", "com.xiaomi.voiceassistant.instruction.card.stream.b");
        symbols.put("renderer", modern ? "wn1.d" : "nj0.c");
        symbols.put("rendererSingleton", symbols.get("renderer") + "#f:a");
        if (modern) {
            symbols.put("duplex", "a41.g");
            symbols.put("duplexSingleton", "a41.g#f:a");
            symbols.put("memoryQuery", "x51.m0");
            symbols.put("memoryBuilder", "x51.m0$e");
            symbols.put("memoryOrigin", "com.xiaomi.voiceassistant.utils.p6");
            symbols.put("queryCard", "p31.y0");
        }
        return symbols;
    }
}
