package tech.ydb.yoj.repository.ydb.client.interceptors;

import lombok.experimental.UtilityClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

@UtilityClass
public final class CommonFullScanCallback {
    private static final Logger log = LoggerFactory.getLogger(CommonFullScanCallback.class);

    public static final Consumer<String> DEFAULT = query -> log.warn("FullScan", new IllegalArgumentException("\"" + query + "\""));
    public static final Consumer<String> FAIL = query -> {
        throw new UnexpectedFullScanQueryError(query);
    };

    public static class UnexpectedFullScanQueryError extends AssertionError {
        public UnexpectedFullScanQueryError(String query) {
            super("Unexpected full scan query : " + query);
        }
    }
}
