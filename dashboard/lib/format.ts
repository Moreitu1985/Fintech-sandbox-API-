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
  const cleaned = input.replace(/[\s ]/g, "").replace(",", ".");
  const match = /^(\d{1,13})(?:\.(\d+))?$/.exec(cleaned);
  if (!match) return null;
  const fraction = match[2] ?? "";
  if (fraction.length > fractionDigits) return null;
  const value = Number(match[1]) * 10 ** fractionDigits + Number(fraction.padEnd(fractionDigits, "0") || "0");
  return Number.isSafeInteger(value) && value > 0 ? value : null;
}
