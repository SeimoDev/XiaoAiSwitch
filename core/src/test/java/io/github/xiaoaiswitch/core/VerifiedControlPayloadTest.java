package io.github.xiaoaiswitch.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class VerifiedControlPayloadTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void packagingMetadataDoesNotChangeAnIdenticalCodeAndResourcesPayload() throws Exception {
        var original = archive(Map.of("AndroidManifest.xml", "old version", "classes.dex", "code", "resources.arsc", "resources"));
        var repackaged = archive(Map.of("classes.dex", "code", "resources.arsc", "resources", "AndroidManifest.xml", "new version", "META-INF/APK.SF", "signature"));
        assertEquals(VerifiedControlPayload.identity(List.of(original)), VerifiedControlPayload.identity(List.of(repackaged)));
    }

    @Test public void additionalSplitAndChangesToCodeOrResourcesInvalidateAReviewedPayload() throws Exception {
        var original = archive(Map.of("classes.dex", "code", "resources.arsc", "resources"));
        String expected = VerifiedControlPayload.identity(List.of(original));
        assertNotEquals(expected, VerifiedControlPayload.identity(List.of(archive(Map.of("classes.dex", "other code", "resources.arsc", "resources")))));
        assertNotEquals(expected, VerifiedControlPayload.identity(List.of(archive(Map.of("classes.dex", "code", "resources.arsc", "other resources")))));
        assertNotEquals(expected, VerifiedControlPayload.identity(List.of(original, archive(Map.of("classes.dex", "split code")))));
    }

    private File archive(Map<String, String> entries) throws Exception {
        var file = folder.newFile();
        try (var output = new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
            for (var entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
            }
        }
        return file;
    }
}
