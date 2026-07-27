import { describe, expect, it } from "vitest";

import {
    authorityFromShareOrigin,
    buildCustomShareUri,
    buildShareLandingPage,
    buildWebShareUrl,
    parseShareOrigin,
    SHARE_LANDING_HEADERS,
} from "../src/share-links.js";

const reference = "AbCdEfGhIjKlMnOpQrStUv";

describe("share links", () => {
    it("strictly parses origins and normalizes default ports", () => {
        expect(parseShareOrigin("HTTPS://Example.COM:443/")).toBe("https://example.com");
        expect(authorityFromShareOrigin("https://example.com")).toEqual({
            host: "example.com", port: 443, useTls: true,
        });
        expect(authorityFromShareOrigin("http://[2001:db8::1]:8080")).toEqual({
            host: "2001:db8::1", port: 8080, useTls: false,
        });
    });

    it.each([
        "ftp://example.com",
        "https://user@example.com",
        "https://example.com/prefix",
        "https://example.com?query=1",
        "https://example.com/#fragment",
    ])("rejects unsafe origin %s", (origin) => {
        expect(() => parseShareOrigin(origin)).toThrow();
    });

    it("generates only locator authority version and opaque reference", () => {
        const custom = buildCustomShareUri(reference, { host: "Example.COM.", port: 8787, useTls: true });
        const web = buildWebShareUrl("https://share.example.test", reference);
        expect(custom).toBe(`pimobile://open/v1/${reference}?host=example.com&port=8787&tls=1`);
        expect(web).toBe(`https://share.example.test/s/v1/${reference}`);
        for (const secret of ["session-id", "bridge-token", "/private/path", "profile-id", "transcript"]) {
            expect(`${custom}${web}`).not.toContain(secret);
        }
    });

    it("renders one metadata-free self-hosted page with required headers", () => {
        const page = buildShareLandingPage("https://share.example.test", reference);
        expect(page).toContain("Open in Pi Mobile");
        expect(page).toContain(`pimobile://open/v1/${reference}?host=share.example.test&amp;port=443&amp;tls=1`);
        for (const metadata of ["session-id", "/home/operator", "cwd", "preview", "title from session", "token"]) {
            expect(page).not.toContain(metadata);
        }
        expect(SHARE_LANDING_HEADERS).toMatchObject({
            "cache-control": "no-store",
            "referrer-policy": "no-referrer",
            "x-content-type-options": "nosniff",
            "x-frame-options": "DENY",
        });
        expect(SHARE_LANDING_HEADERS["content-security-policy"]).toContain("frame-ancestors 'none'");
    });
});
