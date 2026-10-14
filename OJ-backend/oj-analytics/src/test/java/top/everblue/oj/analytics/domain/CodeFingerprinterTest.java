package top.everblue.oj.analytics.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指纹缓存单测（M2）：相同代码两次计算返回同一指纹实例（缓存命中），
 * 不同代码返回不同指纹。
 */
class CodeFingerprinterTest {

    private static final String CODE = """
            #include <bits/stdc++.h>
            using namespace std;
            int main() {
                int n;
                cin >> n;
                long long sum = 0;
                for (int i = 1; i <= n; i++) sum += i;
                cout << sum << endl;
                return 0;
            }
            """;

    @Test
    void sameCodeReturnsCachedFingerprintInstance() {
        var first = CodeFingerprinter.fingerprint(CODE);
        var second = CodeFingerprinter.fingerprint(CODE);
        // 缓存命中：同一实例（ConcurrentHashMap.computeIfAbsent 语义）
        assertSame(first, second);
        assertTrue(first.signature().length == CodeFingerprinter.SIGNATURE_SIZE);
    }

    @Test
    void differentCodeProducesDifferentFingerprint() {
        String other = "int main() { return 0; }";
        var a = CodeFingerprinter.fingerprint(CODE);
        var b = CodeFingerprinter.fingerprint(other);
        assertTrue(CodeFingerprinter.similarity(a, b) < 1.0);
    }

    @Test
    void blankCodeReturnsEmptyFingerprintWithoutCachePollution() {
        var empty = CodeFingerprinter.fingerprint("   ");
        assertTrue(empty.empty());
    }
}
