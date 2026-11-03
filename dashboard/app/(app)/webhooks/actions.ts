"use server";

import type { WebhookEventType } from "@ledgerforge/sdk";
import { LedgerForgeError } from "@ledgerforge/sdk";
import { revalidatePath } from "next/cache";
import { redirect, unstable_rethrow } from "next/navigation";
import { actionError, isId, requireClient, type ActionResult } from "@/lib/auth.ts";

const TYPES: WebhookEventType[] = ["transfer.posted", "account.created"];

export interface CreatedEndpoint {
  id: string;
  secret: string;
}

export async function createEndpoint(_prev: ActionResult<CreatedEndpoint> | null, form: FormData)
  : Promise<ActionResult<CreatedEndpoint>> {
  const lf = await requireClient();
  const eventTypes = TYPES.filter((t) => form.get(t) === "on");
  if (eventTypes.length === 0) return { ok: false, error: "Choose at least one event type." };
  const description = String(form.get("description") ?? "").trim();
  try {
    const created = await lf.webhooks.create({ url: String(form.get("url") ?? "").trim(), eventTypes,
      ...(description ? { description } : {}) });
    revalidatePath("/webhooks");
    // Returned to this one response only: it is never stored by the dashboard or shown again.
    return { ok: true, value: { id: created.endpoint.id, secret: created.secret } };
  } catch (e) {
    return { ok: false, error: await actionError(e) };
  }
}

export async function rotateSecret(_prev: ActionResult<string> | null, form: FormData): Promise<ActionResult<string>> {
  const lf = await requireClient();
  const id = form.get("endpointId");
  if (!isId(id)) return { ok: false, error: "Unknown endpoint." };
  try {
    const rotated = await lf.webhooks.rotateSecret(id);
    return { ok: true, value: rotated.secret };
  } catch (e) {
    return { ok: false, error: await actionError(e) };
  }
}

/** Small actions on one endpoint; each reports through a fixed banner code (see lib/notices.ts). */
export async function endpointAction(form: FormData): Promise<void> {
  const lf = await requireClient();
  const id = String(form.get("endpointId"));
  const op = String(form.get("op"));
  if (!isId(id)) redirect("/webhooks");
  let result: string;
  try {
    switch (op) {
      case "test":
        await lf.webhooks.sendTest(id);
        result = "notice=test-queued";
        break;
      case "enable":
        await lf.webhooks.enable(id);
        result = "notice=enabled";
        break;
      case "retry": {
        const deliveryId = form.get("deliveryId");
        if (!isId(deliveryId)) redirect(`/webhooks/${id}`);
        await lf.webhooks.retryDelivery(deliveryId);
        result = "notice=retry-queued";
        break;
      }
      case "delete":
        await lf.webhooks.delete(id);
        revalidatePath("/webhooks");
        redirect("/webhooks?notice=deleted");
      default:
        redirect(`/webhooks/${id}`);
    }
  } catch (e) {
    unstable_rethrow(e); // Next.js redirects travel as exceptions
    if (e instanceof LedgerForgeError && e.status === 401) await actionError(e);
    result = e instanceof LedgerForgeError ? `notice=failed&code=${encodeURIComponent(e.code)}` : "notice=unavailable";
  }
  revalidatePath(`/webhooks/${id}`);
  redirect(`/webhooks/${id}?${result}`);
}
