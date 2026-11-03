/** Formats minor units in the currency's own precision: 1234567 ZAR -> "R 12 345,67" (en-ZA). */
export function money(minorUnits: number, currency: string): string {
  try {
    const nf = new Intl.NumberFormat("en-ZA", { style: "currency", currency });
    const digits = nf.resolvedOptions().maximumFractionDigits ?? 2;
    return nf.format(minorUnits / 10 ** digits);
  } catch {
    return `${(minorUnits / 100).toFixed(2)} ${currency}`;
  }
}

export function when(iso: string | null | undefined): string {
  if (!iso) return "—";
  return new Intl.DateTimeFormat("en-ZA", { dateStyle: "medium", timeStyle: "medium", timeZone: "Africa/Johannesburg" })
    .format(new Date(iso));
}

export function shortId(id: string): string {
  return id.slice(0, 8);
}

/** Parses a decimal amount typed by a person ("1 250,50" or "1250.50") into minor units, exactly. */
export function parseAmount(input: string, fractionDigits = 2): number | null {
  let cleaned = input.replace(/[\s\u00a0]/g, "");
  if (cleaned.includes(",")) {
    // "1,250" means 1.25 in rand but 1250 to many readers, and 1.250 in a 3-decimal currency: accept a
    // decimal comma only where it can't be confused (at most 2 decimals, and no point as well).
    if (fractionDigits > 2 || cleaned.includes(".")) return null;
    cleaned = cleaned.replace(",", ".");
  }
  const match = /^(\d{1,13})(?:\.(\d+))?$/.exec(cleaned);
  if (!match) return null;
  const fraction = match[2] ?? "";
  if (fraction.length > fractionDigits) return null;
  const value = Number(match[1]) * 10 ** fractionDigits + Number(fraction.padEnd(fractionDigits, "0") || "0");
  return Number.isSafeInteger(value) && value > 0 ? value : null;
}
