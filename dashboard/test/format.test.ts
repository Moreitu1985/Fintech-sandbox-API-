import { strict as assert } from "node:assert";
import { describe, it } from "node:test";
import { money, parseAmount } from "../lib/format.ts";

describe("parseAmount", () => {
  it("turns what people type into exact minor units", () => {
    assert.equal(parseAmount("250"), 25_000);
    assert.equal(parseAmount("250.5"), 25_050);
    assert.equal(parseAmount("1 250,05"), 125_005);
    assert.equal(parseAmount("0.01"), 1);
    assert.equal(parseAmount("0.1"), 10);       // never 9.999... floating point
    assert.equal(parseAmount("19.99"), 1_999);
  });

  it("refuses anything ambiguous", () => {
    for (const bad of ["", "0", "-5", "1.234", "1e3", "abc", "1,2,3", "12.", ".5", "1 2 3.4.5", "1,250", "1.250,00"]) {
      assert.equal(parseAmount(bad), null, bad);
    }
    assert.equal(parseAmount("1,250", 3), null, "ambiguous in a 3-decimal currency");
    assert.equal(parseAmount("1.250", 3), 1_250);
  });
});

describe("money", () => {
  it("formats in the currency's precision", () => {
    assert.match(money(1_234_567, "ZAR"), /12.345,67/);
    assert.match(money(500, "JPY"), /500/);
  });
});
