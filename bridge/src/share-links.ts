import { domainToASCII } from "node:url";

const SHARE_REFERENCE_PATTERN = /^[A-Za-z0-9_-]{22}$/;

export interface ShareAuthority {
    host: string;
    port: number;
    useTls: boolean;
}

export function isValidShareReference(value: unknown): value is string {
    return typeof value === "string" && SHARE_REFERENCE_PATTERN.test(value);
}

export function parseShareOrigin(raw: string | undefined): string | undefined {
    const value = raw?.trim();
    if (!value) return undefined;
    if (/\p{Cc}/u.test(value)) throw new Error("Invalid BRIDGE_SHARE_ORIGIN");

    let parsed: URL;
    try {
        parsed = new URL(value);
    } catch {
        throw new Error("Invalid BRIDGE_SHARE_ORIGIN");
    }

    if ((parsed.protocol !== "http:" && parsed.protocol !== "https:") ||
        !parsed.hostname || parsed.username || parsed.password || parsed.search || parsed.hash ||
        (parsed.pathname !== "/" && parsed.pathname !== "")) {
        throw new Error("BRIDGE_SHARE_ORIGIN must be an http(s) origin without path, userinfo, query, or fragment");
    }

    return parsed.origin;
}

export function authorityFromShareOrigin(origin: string): ShareAuthority {
    const parsed = new URL(origin);
    const useTls = parsed.protocol === "https:";
    return {
        host: normalizeAuthorityHost(parsed.hostname),
        port: parsed.port ? Number(parsed.port) : (useTls ? 443 : 80),
        useTls,
    };
}

export function buildCustomShareUri(reference: string, authority: ShareAuthority): string {
    if (!isValidShareReference(reference)) throw new Error("Invalid share reference");
    const host = encodeURIComponent(normalizeAuthorityHost(authority.host));
    if (!Number.isSafeInteger(authority.port) || authority.port < 1 || authority.port > 65_535) {
        throw new Error("Invalid share authority port");
    }
    return `pimobile://open/v1/${reference}?host=${host}&port=${authority.port}&tls=${authority.useTls ? 1 : 0}`;
}

export function buildWebShareUrl(origin: string, reference: string): string {
    if (!isValidShareReference(reference)) throw new Error("Invalid share reference");
    return `${parseShareOrigin(origin)}/s/v1/${reference}`;
}

export function buildShareLandingPage(origin: string, reference: string): string {
    const customUri = escapeHtml(buildCustomShareUri(reference, authorityFromShareOrigin(origin)));
    return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">" +
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
        "<title>Open in PiPilot</title></head><body><main>" +
        "<h1>Open in PiPilot</h1><p>Authenticate with the configured bridge to open this shared session.</p>" +
        `<p><a rel="noreferrer" href="${customUri}">Open in PiPilot</a></p>` +
        "<p>If the app is not configured, open PiPilot and review the host details first.</p>" +
        "</main></body></html>";
}

export const SHARE_LANDING_HEADERS: Readonly<Record<string, string>> = {
    "content-type": "text/html; charset=utf-8",
    "cache-control": "no-store",
    "referrer-policy": "no-referrer",
    "x-content-type-options": "nosniff",
    "x-frame-options": "DENY",
    "content-security-policy": "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
};

function normalizeAuthorityHost(raw: string): string {
    const unbracketed = raw.startsWith("[") && raw.endsWith("]") ? raw.slice(1, -1) : raw;
    if (!unbracketed || /[\p{Cc}@/\\]/u.test(unbracketed)) throw new Error("Invalid share authority");
    if (unbracketed.includes(":")) {
        if (!/^[0-9a-fA-F:.%]+$/.test(unbracketed)) throw new Error("Invalid IPv6 authority");
        return unbracketed.toLowerCase();
    }
    const ascii = domainToASCII(unbracketed.replace(/\.$/, "")).toLowerCase();
    if (!ascii) throw new Error("Invalid share authority");
    return ascii;
}

function escapeHtml(value: string): string {
    return value.replaceAll("&", "&amp;").replaceAll("\"", "&quot;")
        .replaceAll("<", "&lt;").replaceAll(">", "&gt;");
}
