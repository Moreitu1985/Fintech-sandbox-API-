import { redirect } from "next/navigation";
import { readSession } from "@/lib/auth.ts";
import { LoginForm } from "./login-form.tsx";

export const metadata = { title: "Sign in" };

export default async function LoginPage({ searchParams }: { searchParams: Promise<{ expired?: string }> }) {
  if (await readSession()) redirect("/");
  const { expired } = await searchParams;
  return (
    <main className="login">
      <div className="card">
        <div className="brand">Ledger<span>Forge</span></div>
        <h1>Sign in</h1>
        <p className="muted">Use your tenant&apos;s API key. Use a dedicated key for the dashboard, so you can revoke it on its own.</p>
        {expired && <div className="alert error" role="alert">Your session ended. Sign in again.</div>}
        <LoginForm />
      </div>
    </main>
  );
}
