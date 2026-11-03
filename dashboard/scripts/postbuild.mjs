// The standalone server needs the static assets beside it (Next.js leaves this copy to the deployer).
import { cpSync, existsSync } from "node:fs";

const target = new URL("../.next/standalone/dashboard/.next/static", import.meta.url);
cpSync(new URL("../.next/static", import.meta.url), target, { recursive: true });
if (existsSync(new URL("../public", import.meta.url))) {
  cpSync(new URL("../public", import.meta.url), new URL("../.next/standalone/dashboard/public", import.meta.url),
    { recursive: true });
}
console.log("standalone output ready: node .next/standalone/dashboard/server.js");
