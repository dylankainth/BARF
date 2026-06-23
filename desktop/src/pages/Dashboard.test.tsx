/**
 * Dashboard.test.tsx — RED phase
 *
 * Tests for the camera feed feature. The feature under test does not yet exist:
 * Dashboard renders a static placeholder div regardless of connection state.
 * Tests 3 and 4 (connected branch) MUST fail until the implementation is added.
 * Tests 1 and 2 (disconnected branch) verify pre-existing behaviour that must
 * survive the upcoming change (regression guard).
 */

import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, act } from "@testing-library/react";
import Dashboard from "./Dashboard";

// ── Tauri API ────────────────────────────────────────────────────────────────
// Not available in jsdom; mock the entire module.
vi.mock("@tauri-apps/api/core", () => ({
  invoke: vi.fn().mockResolvedValue("none"),
}));

// ── qrcode ───────────────────────────────────────────────────────────────────
// Uses canvas APIs absent in jsdom.
vi.mock("qrcode", () => ({
  default: {
    toDataURL: vi.fn().mockResolvedValue("data:image/png;base64,fake"),
  },
}));

// ── WebSocket ────────────────────────────────────────────────────────────────
// jsdom ships a real (but limited) WebSocket; stub it out so SerialMonitor
// does not throw on ws:// connections.
class StubWebSocket {
  onopen: (() => void) | null = null;
  onmessage: ((e: MessageEvent) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: ((e: Event) => void) | null = null;
  readyState = 0;
  send(_data: string) {}
  close() {
    this.readyState = 3;
  }
}
vi.stubGlobal("WebSocket", StubWebSocket);

// ── fetch ────────────────────────────────────────────────────────────────────
// Default: all fetches fail (phone not reachable).
beforeEach(() => {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockRejectedValue(new Error("network unavailable in tests"))
  );
});

// ── Helper ───────────────────────────────────────────────────────────────────
/**
 * Render Dashboard with the phone already in the "Connected" state.
 *
 * We simulate what happens when the user types a phone IP into the manual
 * input and the periodic status fetch succeeds: phoneIp is set and
 * phoneStatus transitions to "Connected".
 *
 * We reach phoneIp via the manual IP <input> in QrPairingCard.
 */
async function renderConnected(phoneIp: string) {
  // fetchStatus will be called immediately after phoneIp changes.
  vi.stubGlobal(
    "fetch",
    vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        isMoving: false,
        lastCommand: "none",
        cameraFacing: 0,
      }),
    })
  );

  render(<Dashboard />);

  const input = screen.getByPlaceholderText(/phone ip/i);

  // Use fireEvent to set the value and fire a change event in a single act so
  // the React state update from onPair(ip) is flushed before we query.
  const { fireEvent } = await import("@testing-library/react");
  await act(async () => {
    // Set value directly to avoid character-by-character typing timing issues.
    fireEvent.change(input, { target: { value: phoneIp } });
  });

  // Wait for the polling fetchStatus to resolve and phoneStatus → "Connected".
  // findBy* retries until the element appears or the timeout expires.
  return { phoneIp };
}

// ─────────────────────────────────────────────────────────────────────────────

describe("Dashboard — camera feed section", () => {
  // ── Disconnected state (regression guard) ──────────────────────────────────

  it("shows placeholder text when phone is not connected", async () => {
    await act(async () => {
      render(<Dashboard />);
    });

    // The placeholder div must be visible when there is no phone IP.
    expect(screen.getByText(/connect to phone/i)).toBeInTheDocument();
  });

  it("does not render a camera img pointing at /api/video when phone is not connected", async () => {
    await act(async () => {
      render(<Dashboard />);
    });

    const videoImgs = screen
      .queryAllByRole("img")
      .filter((el) => el.getAttribute("src")?.includes("/api/video"));

    expect(videoImgs).toHaveLength(0);
  });

  // ── Connected state (new feature — EXPECTED TO FAIL until implemented) ─────

  it("renders a camera img whose src contains the phone IP and /api/video when connected", async () => {
    const PHONE_IP = "192.168.1.42";
    await renderConnected(PHONE_IP);

    // This will fail until Dashboard replaces the placeholder <div> with an
    // <img src={`http://${phoneIp}:8080/api/video`} …> when phoneStatus is
    // "Connected".
    const img = await screen.findByRole("img", {
      name: /camera feed/i,
    });

    expect(img).toHaveAttribute(
      "src",
      expect.stringContaining(`http://${PHONE_IP}:8080/api/video`)
    );
  });

  it("camera img has a non-empty alt attribute when phone is connected", async () => {
    const PHONE_IP = "10.0.0.5";
    await renderConnected(PHONE_IP);

    const img = await screen.findByRole("img", {
      name: /camera feed/i,
    });

    // Alt text must be non-empty — required for accessibility.
    expect(img.getAttribute("alt")).toBeTruthy();
  });
});
