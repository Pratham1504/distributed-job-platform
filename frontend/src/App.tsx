import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";

type Session = { accessToken: string; refreshToken: string; expiresIn: number };
type Project = { id: string; name: string; createdAt: string };
type Attempt = { id: string; attemptNumber: number; status: string; startedAt?: string; finishedAt?: string; errorCode?: string; result?: Record<string, unknown> };
type Run = { id: string; runNumber: number; status: string; attemptCount: number; maxAttempts: number; executions: Attempt[] };
type Job = { id: string; jobType: string; status: string; priority: string; createdAt: string; scheduledAt?: string; completedAt?: string; runs: Run[] };
type JobPage = { items: Job[]; nextCursor?: string };
type ApiKey = { id: string; name: string; prefix: string; status: string; createdAt: string; lastUsedAt?: string };
type CreatedApiKey = ApiKey & { plaintextKey: string };

const jobDescriptions: Record<string, string> = {
  GENERATE_REPORT: "Create a report from a selected template.",
  SEND_EMAIL: "Deliver a templated email to one recipient.",
  PROCESS_FILE: "Validate or normalise an authorised object.",
  SEND_NOTIFICATION: "Deliver a completion or failure notification."
};

const storedSession = (): Session | null => {
  try { return JSON.parse(localStorage.getItem("job-platform-session") ?? "null") as Session | null; }
  catch { return null; }
};
function errorMessage(body: unknown, fallback: string) {
  return typeof body === "object" && body !== null && "message" in body && typeof body.message === "string" ? body.message : fallback;
}
function localDate(date: Date) {
  return new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 10);
}
function nextScheduleMinimum() {
  const date = new Date(Math.ceil((Date.now() + 10_000) / 60_000) * 60_000);
  return new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
}
function iso(value: string) { return value ? new Date(value).toISOString() : undefined; }
function display(value?: string) { return value ? new Date(value).toLocaleString() : "—"; }
function statusClass(status: string) { return "status " + status.toLowerCase().replace("_", "-"); }
function jobLabel(type: string) { return type.replaceAll("_", " ").toLowerCase().replace(/\b\w/g, letter => letter.toUpperCase()); }

export default function App() {
  const [session, setSession] = useState<Session | null>(storedSession);
  const [mode, setMode] = useState<"login" | "register">("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [projectName, setProjectName] = useState("");
  const [projects, setProjects] = useState<Project[]>([]);
  const [projectId, setProjectId] = useState("");
  const [jobs, setJobs] = useState<Job[]>([]);
  const [keys, setKeys] = useState<ApiKey[]>([]);
  const [createdKey, setCreatedKey] = useState<CreatedApiKey | null>(null);
  const [keyName, setKeyName] = useState("");
  const [jobType, setJobType] = useState("GENERATE_REPORT");
  const [priority, setPriority] = useState("DEFAULT");
  const [scheduleLater, setScheduleLater] = useState(false);
  const [scheduledAt, setScheduledAt] = useState("");
  const [scheduleMinimum, setScheduleMinimum] = useState(nextScheduleMinimum);
  const [template, setTemplate] = useState("SALES_SUMMARY");
  const [periodStart, setPeriodStart] = useState(() => { const date = new Date(); date.setDate(1); return localDate(date); });
  const [periodEnd, setPeriodEnd] = useState(() => localDate(new Date()));
  const [recipient, setRecipient] = useState("");
  const [variables, setVariables] = useState('{\n  "customerName": "Ada"\n}');
  const [objectRef, setObjectRef] = useState("");
  const [operation, setOperation] = useState("CSV_VALIDATE");
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);

  const request = useCallback(async <T,>(path: string, init: RequestInit = {}): Promise<T> => {
    const response = await fetch(path, {
      ...init,
      headers: {
        ...(init.body ? { "Content-Type": "application/json" } : {}),
        ...(session ? { Authorization: "Bearer " + session.accessToken } : {}),
        ...init.headers
      }
    });
    const body = response.status === 204 ? null : await response.json().catch(() => null);
    if (response.status === 401) {
      localStorage.removeItem("job-platform-session");
      setSession(null);
    }
    if (!response.ok) throw new Error(errorMessage(body, "The request could not be completed."));
    return body as T;
  }, [session]);

  const loadProjects = useCallback(async () => {
    const found = await request<Project[]>("/api/v1/projects");
    setProjects(found);
    setProjectId(current => found.some(project => project.id === current) ? current : (found[0]?.id ?? ""));
  }, [request]);
  const loadJobs = useCallback(async () => {
    if (!projectId) return;
    const page = await request<JobPage>("/api/v1/projects/" + projectId + "/jobs?limit=25");
    setJobs(page.items);
    setLastUpdated(new Date());
  }, [projectId, request]);
  const loadKeys = useCallback(async () => {
    if (projectId) setKeys(await request<ApiKey[]>("/api/v1/projects/" + projectId + "/api-keys"));
  }, [projectId, request]);

  useEffect(() => {
    if (session) void loadProjects().catch(cause => setError(errorMessage(cause, "Could not load projects.")));
  }, [session, loadProjects]);
  useEffect(() => {
    if (!projectId) return;
    void loadJobs().catch(cause => setError(errorMessage(cause, "Could not load jobs.")));
    void loadKeys().catch(cause => setError(errorMessage(cause, "Could not load API keys.")));
  }, [projectId, loadJobs, loadKeys]);
  useEffect(() => {
    if (!projectId) return;
    const timer = window.setInterval(() => void loadJobs().catch(() => undefined), 5_000);
    return () => window.clearInterval(timer);
  }, [projectId, loadJobs]);
  useEffect(() => {
    const timer = window.setInterval(() => setScheduleMinimum(nextScheduleMinimum()), 30_000);
    return () => window.clearInterval(timer);
  }, []);
  useEffect(() => {
    if (!notice) return;
    const timer = window.setTimeout(() => setNotice(null), 6_000);
    return () => window.clearTimeout(timer);
  }, [notice]);

  const activeProject = useMemo(() => projects.find(project => project.id === projectId), [projects, projectId]);
  const summary = useMemo(() => ({
    completed: jobs.filter(job => job.status === "COMPLETED").length,
    inProgress: jobs.filter(job => ["PENDING", "QUEUED", "RUNNING", "RETRY_WAIT"].includes(job.status)).length,
    failed: jobs.filter(job => ["FAILED", "CANCELLED"].includes(job.status)).length
  }), [jobs]);
  const payload = useMemo(() => {
    if (jobType === "GENERATE_REPORT") return { template, periodStart, periodEnd };
    if (jobType === "SEND_EMAIL") {
      try { return { template, recipient, variables: JSON.parse(variables) as Record<string, string> }; }
      catch { return null; }
    }
    if (jobType === "PROCESS_FILE") return { sourceObjectRef: objectRef, operation };
    return { channel: "EMAIL", recipient, template };
  }, [jobType, template, periodStart, periodEnd, recipient, variables, objectRef, operation]);

  async function authenticate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); setError(null);
    try {
      const body = mode === "register" ? { email, password, projectName } : { email, password };
      const next = await request<Session>("/api/v1/auth/" + (mode === "register" ? "register" : "login"), { method: "POST", body: JSON.stringify(body) });
      localStorage.setItem("job-platform-session", JSON.stringify(next));
      setSession(next); setNotice("You are signed in.");
    } catch (cause) { setError(errorMessage(cause, "Sign-in failed.")); }
    finally { setBusy(false); }
  }
  async function createProject(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); setError(null);
    try {
      const project = await request<Project>("/api/v1/projects", { method: "POST", body: JSON.stringify({ name: projectName }) });
      setProjectName(""); await loadProjects(); setProjectId(project.id); setNotice("Project created and selected.");
    } catch (cause) { setError(errorMessage(cause, "Could not create project.")); }
    finally { setBusy(false); }
  }
  function selectJobType(nextType: string) {
    setJobType(nextType);
    setTemplate(nextType === "SEND_EMAIL" ? "ORDER_CONFIRMED" : nextType === "SEND_NOTIFICATION" ? "JOB_COMPLETED" : "SALES_SUMMARY");
  }
  async function submitJob(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); setError(null); setNotice(null);
    if (!projectId) { setError("Choose a project before queuing a job."); setBusy(false); return; }
    if (!payload) { setError("Email variables must be valid JSON."); setBusy(false); return; }
    if (jobType === "GENERATE_REPORT" && periodStart > periodEnd) { setError("Report period end must be on or after the start date."); setBusy(false); return; }
    if (scheduleLater && (!scheduledAt || new Date(scheduledAt).getTime() < Date.now() + 10_000)) {
      setError("Choose a time at least 10 seconds in the future."); setBusy(false); return;
    }
    try {
      const accepted = await request<{ jobId: string }>("/api/v1/projects/" + projectId + "/jobs", {
        method: "POST",
        headers: { "Idempotency-Key": crypto.randomUUID() },
        body: JSON.stringify({ jobType, payload, priority, ...(scheduleLater ? { scheduledAt: iso(scheduledAt) } : {}) })
      });
      setScheduledAt(""); setScheduleLater(false);
      setNotice(jobLabel(jobType) + " job accepted · " + accepted.jobId.slice(0, 8) + "…");
      await loadJobs();
    } catch (cause) { setError(errorMessage(cause, "Job submission failed.")); }
    finally { setBusy(false); }
  }
  async function createKey(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); setError(null);
    try {
      const key = await request<CreatedApiKey>("/api/v1/projects/" + projectId + "/api-keys", { method: "POST", body: JSON.stringify({ name: keyName }) });
      setCreatedKey(key); setKeyName(""); await loadKeys();
    } catch (cause) { setError(errorMessage(cause, "Could not create API key.")); }
    finally { setBusy(false); }
  }
  async function revokeKey(key: ApiKey) {
    if (!window.confirm("Revoke the API key “" + key.name + "”? This cannot be undone.")) return;
    setBusy(true); setError(null);
    try {
      await request<void>("/api/v1/api-keys/" + key.id + "/revoke", { method: "POST" });
      setCreatedKey(null); await loadKeys(); setNotice("API key revoked.");
    } catch (cause) { setError(errorMessage(cause, "Could not revoke API key.")); }
    finally { setBusy(false); }
  }
  async function cancel(job: Job) {
    if (!window.confirm("Cancel " + jobLabel(job.jobType) + "?")) return;
    setBusy(true); setError(null);
    try {
      await request<Job>("/api/v1/projects/" + projectId + "/jobs/" + job.id + "/cancel", { method: "POST" });
      await loadJobs(); setNotice("Job cancelled.");
    } catch (cause) { setError(errorMessage(cause, "Could not cancel job.")); }
    finally { setBusy(false); }
  }
  async function retry(job: Job) {
    setBusy(true); setError(null);
    try {
      await request<{ runId: string }>("/api/v1/projects/" + projectId + "/jobs/" + job.id + "/retry", { method: "POST", headers: { "Idempotency-Key": crypto.randomUUID() } });
      await loadJobs(); setNotice("A new retry run was accepted.");
    } catch (cause) { setError(errorMessage(cause, "Could not start a manual retry.")); }
    finally { setBusy(false); }
  }
  async function copy(value: string, label: string) {
    try { await navigator.clipboard.writeText(value); setNotice(label + " copied to clipboard."); }
    catch { setError("Could not copy the " + label.toLowerCase() + ". Select and copy it manually."); }
  }

  if (!session) return <main className="auth-shell">
    <section className="auth-card surface">
      <div className="brand-mark" aria-hidden="true">↗</div>
      <p className="eyebrow">Distributed Job Platform</p>
      <h1>{mode === "login" ? "Work moves, even while you don’t." : "Set up your job workspace."}</h1>
      <p className="lead">Queue work, schedule it safely, and follow each execution from one clear control centre.</p>
      <form onSubmit={authenticate}>
        {mode === "register" && <label>First project name<input value={projectName} onChange={event => setProjectName(event.target.value)} autoComplete="organization" minLength={3} required /></label>}
        <label>Email<input value={email} onChange={event => setEmail(event.target.value)} autoComplete="email" type="email" required /></label>
        <label>Password<input value={password} onChange={event => setPassword(event.target.value)} autoComplete={mode === "login" ? "current-password" : "new-password"} type="password" minLength={12} required /></label>
        <button className="primary" disabled={busy}>{busy ? "Please wait…" : mode === "login" ? "Sign in" : "Create workspace"}</button>
      </form>
      <button className="link" onClick={() => setMode(mode === "login" ? "register" : "login")}>{mode === "login" ? "Create a new workspace" : "I already have an account"}</button>
      {error && <p className="error inline-message" role="alert">{error}</p>}
    </section>
  </main>;

  return <main className="dashboard">
    <header className="app-header">
      <div className="header-title"><div className="brand-mark" aria-hidden="true">↗</div><div><p className="eyebrow">Distributed Job Platform</p><h1>Job control centre</h1></div></div>
      <div className="header-actions"><span className="live-indicator"><i />Live updates</span><button className="secondary" onClick={() => { localStorage.removeItem("job-platform-session"); setSession(null); }}>Sign out</button></div>
    </header>
    {(error || notice) && <p className={error ? "error banner" : "success banner"} role={error ? "alert" : "status"}>{error ?? notice}</p>}
    <section className="project-bar surface">
      <div className="project-selector">
        <label>Active project<select value={projectId} onChange={event => setProjectId(event.target.value)}>{projects.map(project => <option key={project.id} value={project.id}>{project.name}</option>)}</select></label>
        {activeProject && <div className="project-id"><span>Project ID</span><code title={activeProject.id}>{activeProject.id}</code><button className="icon-button" title="Copy project ID" aria-label="Copy project ID" onClick={() => void copy(activeProject.id, "Project ID")}>⧉</button></div>}
      </div>
      <form onSubmit={createProject} className="new-project"><input value={projectName} onChange={event => setProjectName(event.target.value)} placeholder="New project name" minLength={3} aria-label="New project name" required /><button className="secondary" disabled={busy}>Add project</button></form>
    </section>
    {projectId && <>
      <section className="summary-grid" aria-label="Recent job summary">
        <article className="summary-card surface"><span>Recent jobs</span><strong>{jobs.length}</strong><small>Latest 25 jobs</small></article>
        <article className="summary-card surface in-progress"><span>In progress</span><strong>{summary.inProgress}</strong><small>Queued, running, or scheduled</small></article>
        <article className="summary-card surface completed"><span>Completed</span><strong>{summary.completed}</strong><small>Recent successful work</small></article>
        <article className="summary-card surface failed"><span>Needs attention</span><strong>{summary.failed}</strong><small>Failed or cancelled</small></article>
      </section>
      <div className="workspace-grid">
        <section className="panel surface submit">
          <div className="panel-heading"><div><p className="eyebrow">New work</p><h2>Queue a job</h2></div><span>Durably accepted</span></div>
          <form onSubmit={submitJob}>
            <label>Job type<select value={jobType} onChange={event => selectJobType(event.target.value)}><option value="GENERATE_REPORT">Generate report</option><option value="SEND_EMAIL">Send email</option><option value="PROCESS_FILE">Process file</option><option value="SEND_NOTIFICATION">Send notification</option></select><small>{jobDescriptions[jobType]}</small></label>
            <div className="two-columns"><label>Priority<select value={priority} onChange={event => setPriority(event.target.value)}><option>HIGH</option><option>DEFAULT</option><option>LOW</option></select></label><label className="schedule-toggle"><input type="checkbox" checked={scheduleLater} onChange={event => { setScheduleLater(event.target.checked); if (!event.target.checked) setScheduledAt(""); }} />Schedule for later</label></div>
            {scheduleLater && <label className="schedule-field">Run once at<input type="datetime-local" value={scheduledAt} min={scheduleMinimum} onChange={event => setScheduledAt(event.target.value)} required /><small>Local time. The earliest available time is safely in the future.</small></label>}
            {jobType === "GENERATE_REPORT" && <><label>Report template<select value={template} onChange={event => setTemplate(event.target.value)}><option>SALES_SUMMARY</option><option>JOB_AUDIT</option></select></label><div className="two-columns"><label>Period start<input type="date" value={periodStart} max={periodEnd || undefined} onChange={event => setPeriodStart(event.target.value)} required /></label><label>Period end<input type="date" value={periodEnd} min={periodStart || undefined} onChange={event => setPeriodEnd(event.target.value)} required /></label></div></>}
            {jobType === "SEND_EMAIL" && <><label>Email template<select value={template} onChange={event => setTemplate(event.target.value)}><option>ORDER_CONFIRMED</option><option>JOB_FAILED</option></select></label><label>Recipient<input type="email" value={recipient} onChange={event => setRecipient(event.target.value)} placeholder="name@example.com" required /></label><label>Variables (JSON)<textarea value={variables} onChange={event => setVariables(event.target.value)} rows={4} spellCheck="false" required /></label></>}
            {jobType === "PROCESS_FILE" && <><label>Authorised object reference<input value={objectRef} onChange={event => setObjectRef(event.target.value)} placeholder="projects/acme/source.csv" required /></label><label>Transformation<select value={operation} onChange={event => setOperation(event.target.value)}><option>CSV_VALIDATE</option><option>JSON_NORMALIZE</option></select></label></>}
            {jobType === "SEND_NOTIFICATION" && <><label>Notification template<select value={template} onChange={event => setTemplate(event.target.value)}><option>JOB_COMPLETED</option><option>JOB_FAILED</option></select></label><label>Recipient<input type="email" value={recipient} onChange={event => setRecipient(event.target.value)} placeholder="name@example.com" required /></label></>}
            <button className="primary submit-button" disabled={busy}>{busy ? "Submitting…" : scheduleLater ? "Schedule job" : "Queue now"}</button>
          </form>
        </section>
        <aside className="side-stack">
          <section className="panel surface keys">
            <div className="panel-heading"><div><p className="eyebrow">Programmatic access</p><h2>API keys</h2></div><span>Project-scoped</span></div>
            {createdKey && <div className="secret"><div><strong>Copy your new key now</strong><p>This value is shown only once.</p></div><code>{createdKey.plaintextKey}</code><div className="secret-actions"><button className="secondary" onClick={() => void copy(createdKey.plaintextKey, "API key")}>Copy key</button><button className="link compact" onClick={() => setCreatedKey(null)}>I copied it</button></div></div>}
            <form className="new-key" onSubmit={createKey}><input value={keyName} onChange={event => setKeyName(event.target.value)} placeholder="Client name, e.g. CI" minLength={3} aria-label="API key client name" required /><button disabled={busy}>Create key</button></form>
            <div className="key-list">{keys.length === 0 ? <p className="muted">No API keys yet.</p> : keys.map(key => <div key={key.id}><span><strong>{key.name}</strong><code>{key.prefix}…</code></span><span className={statusClass(key.status)}>{key.status}</span>{key.status === "ACTIVE" && <button className="danger" onClick={() => void revokeKey(key)} disabled={busy}>Revoke</button>}</div>)}</div>
          </section>
          <section className="tip-card"><strong>How this runs</strong><p>Scheduled work waits until its chosen time. Jobs queued now start as worker capacity becomes available.</p></section>
        </aside>
      </div>
      <section className="panel surface jobs">
        <div className="panel-heading"><div><p className="eyebrow">Activity</p><h2>Recent jobs</h2></div><div className="refresh-info"><span>{lastUpdated ? "Updated " + lastUpdated.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }) : "Loading…"}</span><button className="secondary" onClick={() => void loadJobs()} disabled={busy}>Refresh</button></div></div>
        {jobs.length === 0 ? <div className="empty-state"><div aria-hidden="true">⌁</div><h3>No jobs yet</h3><p>Queue your first job above. Its full run and attempt history will appear here.</p></div> : <div className="job-list">{jobs.map(job => <article key={job.id} className="job-card"><div className="job-title"><div><strong>{jobLabel(job.jobType)}</strong><small>{display(job.createdAt)} · {job.priority} priority</small></div><span className={statusClass(job.status)}>{job.status.replace("_", " ")}</span></div><div className="job-facts"><span><b>Schedule</b>{job.scheduledAt ? display(job.scheduledAt) : "Now"}</span><span><b>Attempts</b>{job.runs.reduce((sum, run) => sum + run.executions.length, 0)}</span><span><b>Completed</b>{display(job.completedAt)}</span></div><div className="job-actions">{["PENDING", "QUEUED"].includes(job.status) && <button className="danger" onClick={() => void cancel(job)} disabled={busy}>Cancel</button>}{job.status === "FAILED" && <button className="secondary" onClick={() => void retry(job)} disabled={busy}>Retry</button>}<details><summary>Run and attempt history</summary>{job.runs.map(run => <div className="run" key={run.id}><p><strong>Run {run.runNumber}</strong> <span className={statusClass(run.status)}>{run.status}</span> · {run.attemptCount}/{run.maxAttempts} attempts</p>{run.executions.map(attempt => <div className="attempt" key={attempt.id}><span>Attempt {attempt.attemptNumber}: <strong>{attempt.status}</strong></span><span>{display(attempt.finishedAt ?? attempt.startedAt)}</span>{attempt.errorCode && <span className="error">{attempt.errorCode}</span>}{attempt.result && <code>{JSON.stringify(attempt.result)}</code>}</div>)}</div>)}</details></div></article>)}</div>}
      </section>
    </>}
  </main>;
}
