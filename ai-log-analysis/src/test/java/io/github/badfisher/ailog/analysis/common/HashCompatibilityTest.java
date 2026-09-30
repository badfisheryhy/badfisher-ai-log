package io.github.badfisher.ailog.analysis.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.junit.jupiter.api.Test;

import io.github.badfisher.ailog.analysis.issue.ErrorContentNormalizer;
import io.github.badfisher.ailog.domain.text.Sha256;

/** Fixed vectors protect existing hashes when the shared mechanics are refactored. */
class HashCompatibilityTest {

    @Test
    void matchesKnownAsciiAndEmptyVectors() {
        assertThat(Sha256.sha256(""))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(Sha256.sha256("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(Sha256.sha256(" abc ")).isNotEqualTo(Sha256.sha256("abc"));
    }

    @Test
    void hashesUtf8WithoutUsingThePlatformCharset() {
        assertThat(Sha256.sha256("中文"))
                .isEqualTo("72726d8818f693066ceb69afa364218b692e62ea92b385782363780f47529c21");
    }

    @Test
    void preservesUnsignedBytesAndLeadingZeros() {
        assertThat(Sha256.toHex(new byte[] {0, 15, 16, (byte) 128, (byte) 255}))
                .isEqualTo("000f1080ff");
    }

    @Test
    void incrementalDigestsHaveIndependentState() {
        MessageDigest first = Sha256.newDigest();
        MessageDigest second = Sha256.newDigest();
        assertThat(first).isNotSameAs(second);

        first.update("a".getBytes(StandardCharsets.UTF_8));
        second.update("xyz".getBytes(StandardCharsets.UTF_8));
        first.update("bc".getBytes(StandardCharsets.UTF_8));

        assertThat(Sha256.toHex(first.digest())).isEqualTo(Sha256.sha256("abc"));
        assertThat(Sha256.toHex(second.digest())).isEqualTo(Sha256.sha256("xyz"));
    }

    @Test
    void retainsTheExistingSampleKeyFormat() {
        assertThat(new ErrorContentNormalizer().sampleKey(null, "HTTP 503 x=123"))
                .isEqualTo("bda45233c3a97a18358cbb5eb15d13141e74793d74fe863f8dbbdc9db20caf4a");
    }

    @Test
    void doesNotSilentlyConvertNullToAnEmptyHash() {
        assertThatThrownBy(() -> Sha256.sha256(null))
                .isInstanceOf(NullPointerException.class);
    }
}
