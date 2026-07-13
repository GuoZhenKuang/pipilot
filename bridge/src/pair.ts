import "dotenv/config";

import QRCode from "qrcode";

import { parseBridgeConfig } from "./config.js";
import { createHostPairingPayload } from "./pairing.js";

function hostArgument(args: string[]): string | undefined {
    const hostIndex = args.indexOf("--host");
    if (hostIndex < 0) return undefined;

    const host = args[hostIndex + 1]?.trim();
    if (!host) throw new Error("--host requires a hostname");
    return host;
}

async function main(): Promise<void> {
    const config = parseBridgeConfig();
    const payload = createHostPairingPayload(config, hostArgument(process.argv.slice(2)));
    const qrCode = await QRCode.toString(JSON.stringify(payload), {
        type: "terminal",
        small: true,
        errorCorrectionLevel: "M",
    });

    process.stdout.write("\nScan this code in Pi Mobile: Hosts → Scan QR\n\n");
    process.stdout.write(qrCode);
    process.stdout.write(`\nHost: ${payload.host}:${payload.port}\n`);
    process.stdout.write("Keep this terminal QR private because it contains the bridge token.\n\n");
}

void main().catch((error: unknown) => {
    const message = error instanceof Error ? error.message : String(error);
    process.stderr.write(`Unable to create pairing QR: ${message}\n`);
    process.exitCode = 1;
});
