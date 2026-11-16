package app.autoturn.vpn;

import static org.junit.Assert.*;
import org.junit.Test;

public class ConnectionProbeTest {
    @Test public void classifiesOutcomes() {
        assertEquals(ConnectionProbe.Result.HEALTHY, ConnectionProbe.classify(true, true, true));
        assertEquals(ConnectionProbe.Result.NO_NETWORK, ConnectionProbe.classify(false, true, false));
        assertEquals(ConnectionProbe.Result.NO_NETWORK, ConnectionProbe.classify(false, false, false));
        assertEquals(ConnectionProbe.Result.FAILED, ConnectionProbe.classify(false, true, true));
        assertEquals(ConnectionProbe.Result.VPN_MISSING, ConnectionProbe.classify(false, false, true));
    }

    @Test public void underlyingNetworkNeverIsAVpn() {
        assertTrue(ConnectionProbe.isUnderlyingInternet(false, true));
        assertFalse(ConnectionProbe.isUnderlyingInternet(true, true));
        assertFalse(ConnectionProbe.isUnderlyingInternet(false, false));
    }

    @Test public void httpStatusParsing() {
        assertTrue(ConnectionProbe.successfulHttpStatus("HTTP/1.1 204 No Content"));
        assertTrue(ConnectionProbe.successfulHttpStatus("HTTP/1.0 503"));
        assertFalse(ConnectionProbe.successfulHttpStatus("HTTP/2 200"));
        assertFalse(ConnectionProbe.successfulHttpStatus("garbage"));
        assertFalse(ConnectionProbe.successfulHttpStatus(null));
        assertTrue(ConnectionProbe.isHttpResponse(100));
        assertTrue(ConnectionProbe.isHttpResponse(599));
        assertFalse(ConnectionProbe.isHttpResponse(99));
        assertFalse(ConnectionProbe.isHttpResponse(600));
    }

    @Test public void probeAnySucceedsWhenOneEndpointAnswers() {
        assertTrue(ConnectionProbe.probeAny(address -> address.contains("wikipedia") || address.contains("example"), false));
        assertFalse(ConnectionProbe.probeAny(address -> false, false));
    }
}
