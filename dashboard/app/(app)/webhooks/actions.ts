"use server";

import type { WebhookEventType } from "@ledgerforge/sdk";
import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { describe, requireClient, type ActionResult } from "@/lib/auth.ts";

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
    return { ok: false, error: describe(e) };
  }
}

export async function rotateSecret(_prev: ActionResult<string> | null, form: FormData): Promise<ActionResult<string>> {
  const lf = await requireClient();
  try {
    const rotated = await lf.webhooks.rotateSecret(String(form.get("endpointId")));
    return { ok: true, value: rotated.secret };
  } catch (e) {
    return { ok: false, error: describe(e) };
  }
}

/** Small actions on one endpoint; each reports through the page's ?notice= banner. */
export async function endpointAction(form: FormData): Promise<void> {
  const lf = await requireClient();
  const id = String(form.get("endpointId"));
  const op = String(form.get("op"));
  let notice: string;
  try {
    switch (op) {
      case "test":
        await lf.webhooks.sendTest(id);
        notice = "Test event queued. Refresh in a moment to see the delivery.";
        break;
      case "enable":
        await lf.webhooks.enable(id);
        notice = "Endpoint enabled.";
        break;
      case "retry":
        await lf.webhooks.retryDelivery(String(form.get("deliveryId")));
        notice = "Delivery queued again.";
        break;
      case "delete":
        await lf.webhooks.delete(id);
        revalidatePath("/webhooks");
        redirect("/webhooks?notice=" + encodeURIComponent("Endpoint deleted. Pending deliveries were cancelled."));
      default:
        notice = "Unknown action.";
    }
  } catch (e) {
    if (e instanceof Error && "digest" in e) throw e; // Next.js redirect
    notice = describe(e);
  }
  revalidatePath(`/webhooks/${id}`);
  redirect(`/webhooks/${id}?notice=${encodeURIComponent(notice)}`);
}
