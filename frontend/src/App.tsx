import { FormEvent, useState } from "react";

type JobAccepted = { jobId: string; runId: string; status: string; statusUrl: string };

function App() {
  const [projectId, setProjectId] = useState("");
  const [template, setTemplate] = useState("SALES_SUMMARY");
  const [start, setStart] = useState("");
  const [end, setEnd] = useState("");
  const [result, setResult] = useState<JobAccepted | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setResult(null);
    try {
      const response = await fetch(`/api/v1/projects/${projectId}/jobs`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Idempotency-Key": crypto.randomUUID()
        },
        body: JSON.stringify({
          jobType: "GENERATE_REPORT",
          payload: { template, periodStart: start, periodEnd: end },
          priority: "DEFAULT"
        })
      });
      const body = await response.json();
      if (!response.ok) throw new Error(body.message ?? "Job submission failed.");
      setResult(body as JobAccepted);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Job submission failed.");
    }
  }

  return (
    <main>
      <section className="hero">
        <p className="eyebrow">Distributed Job Platform</p>
        <h1>Submit a report job</h1>
        <p>The first dashboard view is ready for the API and worker flow.</p>
      </section>
      <form onSubmit={submit}>
        <label>Project ID<input value={projectId} onChange={(e) => setProjectId(e.target.value)} placeholder="UUID from your project" required /></label>
        <label>Report template<select value={template} onChange={(e) => setTemplate(e.target.value)}><option>SALES_SUMMARY</option><option>JOB_AUDIT</option></select></label>
        <label>Period start<input type="date" value={start} onChange={(e) => setStart(e.target.value)} required /></label>
        <label>Period end<input type="date" value={end} onChange={(e) => setEnd(e.target.value)} required /></label>
        <button type="submit">Queue report</button>
      </form>
      {result && <p className="success">Job <code>{result.jobId}</code> accepted with status <strong>{result.status}</strong>.</p>}
      {error && <p className="error">{error}</p>}
    </main>
  );
}

export default App;
