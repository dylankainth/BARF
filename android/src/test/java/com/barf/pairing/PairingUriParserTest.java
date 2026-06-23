package com.barf.pairing;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Unit tests for {@link PairingActivity#parsePairingUri(String)}.
 *
 * All tests in this class are RED: they will fail until
 * {@code parsePairingUri} is extracted as a package-private static helper
 * and the {@code PairingParams} inner class is created.
 *
 * No Android framework is required; the method under test is pure
 * string-parsing logic. Android stubs return defaults anyway via
 * {@code returnDefaultValues = true} in build.gradle.
 */
public class PairingUriParserTest {

    // ── happy path ────────────────────────────────────────────────────────────

    /**
     * Valid URI with all three required params (ip, key, port) must produce a
     * non-null PairingParams with the correct field values.
     */
    @Test
    public void parsePairingUri_validUri_returnsCorrectDesktopIp() {
        PairingActivity.PairingParams result =
                PairingActivity.parsePairingUri(
                        "barf://pair?ip=192.168.1.10&key=abc123&port=9876");

        assertNotNull("parsePairingUri must return non-null for a valid URI", result);
        assertEquals("desktopIp must equal the ip query param",
                "192.168.1.10", result.desktopIp);
    }

    @Test
    public void parsePairingUri_validUri_returnsCorrectPairKey() {
        PairingActivity.PairingParams result =
                PairingActivity.parsePairingUri(
                        "barf://pair?ip=192.168.1.10&key=abc123&port=9876");

        assertNotNull("parsePairingUri must return non-null for a valid URI", result);
        assertEquals("pairKey must equal the key query param",
                "abc123", result.pairKey);
    }

    @Test
    public void parsePairingUri_validUri_returnsCorrectPort() {
        PairingActivity.PairingParams result =
                PairingActivity.parsePairingUri(
                        "barf://pair?ip=192.168.1.10&key=abc123&port=9876");

        assertNotNull("parsePairingUri must return non-null for a valid URI", result);
        assertEquals("port must equal the port query param",
                9876, result.port);
    }

    // ── missing required params ───────────────────────────────────────────────

    /**
     * A URI that is missing the {@code ip} parameter must return null.
     */
    @Test
    public void parsePairingUri_missingIpParam_returnsNull() {
        PairingActivity.PairingParams result =
                PairingActivity.parsePairingUri(
                        "barf://pair?key=abc123&port=9876");

        assertNull("parsePairingUri must return null when ip param is absent", result);
    }

    /**
     * A URI that is missing the {@code key} parameter must return null.
     */
    @Test
    public void parsePairingUri_missingKeyParam_returnsNull() {
        PairingActivity.PairingParams result =
                PairingActivity.parsePairingUri(
                        "barf://pair?ip=192.168.1.10&port=9876");

        assertNull("parsePairingUri must return null when key param is absent", result);
    }

    // ── WireGuard params ──────────────────────────────────────────────────────

    /**
     * A URI containing all four WireGuard params (wg_ip, wg_key, wg_client,
     * wg_port) must parse each one into the corresponding PairingParams field.
     */
    @Test
    public void parsePairingUri_withAllWireGuardParams_parsesWgServerIp() {
        PairingActivity.PairingParams result =
                PairingActivity.parsePairingUri(
                        "barf://pair?ip=10.0.0.1&key=secret&port=9876"
                                + "&wg_ip=10.8.0.1&wg_key=PUBKEY=="
                                + "&wg_client=10.8.0.2&wg_port=51820");

        assertNotNull("parsePairingUri must return non-null for a full URI", result);
        assertEquals("wgServerIp must equal the wg_ip query param",
                "10.8.0.1", result.wgServerIp);
    }

    @Test
    public void parsePairingUri_withAllWireGuardParams_parsesWgPublicKey() {
        PairingActivity.PairingParams result =
                PairingActivity.parsePairingUri(
                        "barf://pair?ip=10.0.0.1&key=secret&port=9876"
                                + "&wg_ip=10.8.0.1&wg_key=PUBKEY=="
                                + "&wg_client=10.8.0.2&wg_port=51820");

        assertNotNull("parsePairingUri must return non-null for a full URI", result);
        assertEquals("wgPublicKey must equal the wg_key query param",
                "PUBKEY==", result.wgPublicKey);
    }

    @Test
    public void parsePairingUri_withAllWireGuardParams_parsesWgClientIp() {
        PairingActivity.PairingParams result =
                PairingActivity.parsePairingUri(
                        "barf://pair?ip=10.0.0.1&key=secret&port=9876"
                                + "&wg_ip=10.8.0.1&wg_key=PUBKEY=="
                                + "&wg_client=10.8.0.2&wg_port=51820");

        assertNotNull("parsePairingUri must return non-null for a full URI", result);
        assertEquals("wgClientIp must equal the wg_client query param",
                "10.8.0.2", result.wgClientIp);
    }

    @Test
    public void parsePairingUri_withAllWireGuardParams_parsesWgPort() {
        PairingActivity.PairingParams result =
                PairingActivity.parsePairingUri(
                        "barf://pair?ip=10.0.0.1&key=secret&port=9876"
                                + "&wg_ip=10.8.0.1&wg_key=PUBKEY=="
                                + "&wg_client=10.8.0.2&wg_port=51820");

        assertNotNull("parsePairingUri must return non-null for a full URI", result);
        assertEquals("wgPort must equal the wg_port query param",
                51820, result.wgPort);
    }

    // ── malformed input ───────────────────────────────────────────────────────

    /**
     * A URI with no query-string (no {@code ?}) must return null without
     * throwing any exception.
     */
    @Test
    public void parsePairingUri_malformedUriNoQueryString_returnsNullWithoutThrowing() {
        PairingActivity.PairingParams result =
                PairingActivity.parsePairingUri("barf://pair");

        assertNull("parsePairingUri must return null for a URI with no query string", result);
    }

    // ── default port ──────────────────────────────────────────────────────────

    /**
     * When the {@code port} param is absent, the port field must default to
     * 9876 (matching the hard-coded default already present in handleQrCode).
     */
    @Test
    public void parsePairingUri_missingPortParam_defaultsTo9876() {
        PairingActivity.PairingParams result =
                PairingActivity.parsePairingUri(
                        "barf://pair?ip=192.168.1.10&key=abc123");

        assertNotNull("parsePairingUri must return non-null even without a port param", result);
        assertEquals("port must default to 9876 when the port param is absent",
                9876, result.port);
    }
}
