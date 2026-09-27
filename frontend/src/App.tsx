import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";

type Session = { accessToken: string; refreshToken: string; expiresIn: number };
type Project = { id: string; name: string; createdAt: string };
type Attempt = { id: string; attemptNumber: number; status: string; startedAt?: string; finishedAt?: string; errorCode?: string; result?: Record<string, unknown>; resultRef?: string };
type Run = { id: string; runNumber: number; status: string; attemptCount: number; maxAttempts: number; executions: Attempt[] };
type Job = { id: string; jobType: string; status: string; priority: string; createdAt: string; scheduledAt?: string; completedAt?: string; runs: Run[] };
type JobPage = { items: Job[]; nextCursor?: string };
type ApiKey = { id: string; name: string; prefix: string; status: string; createdAt: string; lastUsedAt?: string };
type CreatedApiKey = ApiKey & { plaintextKey: string };
type JobFilterStatus = "ALL" | "PENDING" | "QUEUED" | "RUNNING" | "RETRY_WAIT" | "COMPLETED" | "FAILED" | "CANCELLED";
type Theme = "light" | "dark";
type FileAsset = { id: string; kind: string; filename: string; contentType: string; sizeBytes: number; sha256: string; rowCount?: number; header?: string[]; createdAt: string };
type CsvPreview = { header: string[]; rows: string[][]; hasMore: boolean };
type CsvReviewTab = "valid" | "rejected" | "duplicates";
type Route = { page: "dashboard" | "files" | "api" | "job" | "result"; jobId?: string };

const maximumCsvUploadBytes = 10 * 1024 * 1024;

class SupersededRequestError extends Error {
  constructor() { super("This request belongs to a previous session."); }
}

const jobDescriptions: Record<string, string> = {
  GENERATE_REPORT: "Creates a local report artifact. The simulated provider finishes in about 1–2 seconds.",
  SEND_EMAIL: "Delivers a local email simulation in about 1–2 seconds.",
  PROCESS_FILE: "Upload a CSV, then validate it or create a cleaned, deduplicated version.",
  SEND_NOTIFICATION: "Delivers a local notification simulation in about 1–2 seconds."
};

const storedSession = (): Session | null => {
  try { return JSON.parse(localStorage.getItem("job-platform-session") ?? "null") as Session | null; }
  catch { return null; }
};
const storedTheme = (): Theme => {
  const saved = localStorage.getItem("job-platform-theme");
  if (saved === "light" || saved === "dark") return saved;
  return window.matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark";
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
function elapsedTime(start?: string, end?: string) {
  if (!start || !end) return null;
  const milliseconds = new Date(end).getTime() - new Date(start).getTime();
  if (!Number.isFinite(milliseconds) || milliseconds < 0) return null;
  const seconds = Math.round(milliseconds / 1_000);
  if (seconds < 60) return seconds + " " + (seconds === 1 ? "second" : "seconds");
  const minutes = Math.floor(seconds / 60);
  const remainingSeconds = seconds % 60;
  if (minutes < 60) return minutes + " " + (minutes === 1 ? "minute" : "minutes") + (remainingSeconds ? " " + remainingSeconds + " seconds" : "");
  const hours = Math.floor(minutes / 60);
  const remainingMinutes = minutes % 60;
  return hours + " " + (hours === 1 ? "hour" : "hours") + (remainingMinutes ? " " + remainingMinutes + " minutes" : "");
}
function statusClass(status: string) { return "status " + status.toLowerCase().replace("_", "-"); }
function jobLabel(type: string) { return type.replaceAll("_", " ").toLowerCase().replace(/\b\w/g, letter => letter.toUpperCase()); }
function resultMessage(jobType: string, hasArtifact: boolean) {
  if (jobType === "GENERATE_REPORT") return {
    eyebrow: "Report output",
    title: hasArtifact ? "Your report is ready" : "Report generation recorded",
    description: hasArtifact ? "The generated report is ready to download." : "This development workflow retained the execution record in this workspace."
  };
  if (jobType === "SEND_EMAIL") return {
    eyebrow: "Email delivery",
    title: "Local delivery accepted",
    description: "The local provider accepted the request. This development workflow records the outcome but does not create a downloadable file."
  };
  if (jobType === "SEND_NOTIFICATION") return {
    eyebrow: "Notification delivery",
    title: "Local delivery accepted",
    description: "The local provider accepted the request. This development workflow records the outcome but does not create a downloadable file."
  };
  return {
    eyebrow: "Execution output",
    title: hasArtifact ? "Result ready" : "Execution recorded",
    description: hasArtifact ? "The generated result is ready to download." : "This development workflow retained the execution record in this workspace."
  };
}
function artifactReference(attempt: Attempt) {
  const candidate = attempt.resultRef ?? attempt.result?.artifactRef;
  return typeof candidate === "string" && candidate.startsWith("/api/v1/projects/") ? candidate : null;
}
function stringResult(result: Record<string, unknown> | undefined, field: string) {
  const value = result?.[field];
  return typeof value === "string" ? value : null;
}
function numberResult(result: Record<string, unknown> | undefined, field: string) {
  const value = result?.[field];
  return typeof value === "number" ? value : 0;
}
function currentRoute(pathname = window.location.pathname): Route {
  const result = pathname.match(/^\/jobs\/([^/]+)\/result\/?$/);
  if (result) return { page: "result", jobId: decodeURIComponent(result[1]) };
  const job = pathname.match(/^\/jobs\/([^/]+)\/?$/);
  if (job) return { page: "job", jobId: decodeURIComponent(job[1]) };
  if (pathname === "/files" || pathname === "/files/") return { page: "files" };
  if (pathname === "/api" || pathname === "/api/") return { page: "api" };
  return { page: "dashboard" };
}
function routeTitle(route: Route) {
  return route.page === "files" ? "Files" : route.page === "api" ? "API setup" : route.page === "job" ? "Job details" : route.page === "result" ? "Job result" : "Dashboard";
}

export default function App() {
  const [session, setSession] = useState<Session | null>(storedSession);
  const sessionRef = useRef<Session | null>(session);
  const sessionGeneration = useRef(0);
  const [theme, setTheme] = useState<Theme>(storedTheme);
  const [mode, setMode] = useState<"login" | "register">("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [projectName, setProjectName] = useState("");
  const [projects, setProjects] = useState<Project[]>([]);
  const [projectId, setProjectId] = useState("");
  const [jobs, setJobs] = useState<Job[]>([]);
  const [statusFilter, setStatusFilter] = useState<JobFilterStatus>("ALL");
  const [typeFilter, setTypeFilter] = useState("ALL");
  const [priorityFilter, setPriorityFilter] = useState("ALL");
  const [keys, setKeys] = useState<ApiKey[]>([]);
  const [createdKey, setCreatedKey] = useState<CreatedApiKey | null>(null);
  const [keyPendingRevocation, setKeyPendingRevocation] = useState<ApiKey | null>(null);
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
  const [sourceFile, setSourceFile] = useState<File | null>(null);
  const [sourceAssets, setSourceAssets] = useState<FileAsset[]>([]);
  const [selectedSourceAssetId, setSelectedSourceAssetId] = useState("");
  const sourceFileInput = useRef<HTMLInputElement>(null);
  const [sourceFileError, setSourceFileError] = useState<string | null>(null);
  const [operation, setOperation] = useState("CSV_VALIDATE");
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [retryingJobId, setRetryingJobId] = useState<string | null>(null);
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);
  const [route, setRoute] = useState<Route>(() => currentRoute());
  const [routedJob, setRoutedJob] = useState<Job | null>(null);
  const [libraryUploadPending, setLibraryUploadPending] = useState(false);
  const [csvReviewTab, setCsvReviewTab] = useState<CsvReviewTab>("valid");
  const [csvPreviews, setCsvPreviews] = useState<Partial<Record<CsvReviewTab, CsvPreview>>>({});
  const [csvPreviewLoading, setCsvPreviewLoading] = useState(false);
  const [csvPreviewError, setCsvPreviewError] = useState<string | null>(null);
  const [apiExampleType, setApiExampleType] = useState<"GENERATE_REPORT" | "PROCESS_FILE">("GENERATE_REPORT");

  const clearWorkspace = useCallback(() => {
    setProjects([]);
    setProjectId("");
    setJobs([]);
    setKeys([]);
    setCreatedKey(null);
    setKeyPendingRevocation(null);
    setSourceAssets([]);
    setSelectedSourceAssetId("");
    setSourceFile(null);
    setSourceFileError(null);
    setSubmitError(null);
    setRoutedJob(null);
    setCsvPreviews({});
    setCsvPreviewError(null);
    setCsvPreviewLoading(false);
    setLastUpdated(null);
    if (sourceFileInput.current) sourceFileInput.current.value = "";
  }, []);

  const endSession = useCallback(() => {
    sessionGeneration.current += 1;
    sessionRef.current = null;
    localStorage.removeItem("job-platform-session");
    clearWorkspace();
    setError(null);
    setNotice(null);
    setBusy(false);
    setSession(null);
  }, [clearWorkspace]);

  const beginSession = useCallback((next: Session) => {
    sessionGeneration.current += 1;
    sessionRef.current = next;
    clearWorkspace();
    localStorage.setItem("job-platform-session", JSON.stringify(next));
    setSession(next);
  }, [clearWorkspace]);

  const request = useCallback(async <T,>(path: string, init: RequestInit = {}): Promise<T> => {
    const requestSession = sessionRef.current;
    const requestGeneration = sessionGeneration.current;
    const response = await fetch(path, {
      ...init,
      headers: {
        ...(init.body ? { "Content-Type": "application/json" } : {}),
        ...(requestSession ? { Authorization: "Bearer " + requestSession.accessToken } : {}),
        ...init.headers
      }
    });
    const body = response.status === 204 ? null : await response.json().catch(() => null);
    if (requestGeneration !== sessionGeneration.current) throw new SupersededRequestError();
    if (response.status === 401) {
      endSession();
    }
    if (!response.ok) throw new Error(errorMessage(body, "The request could not be completed."));
    return body as T;
  }, [endSession]);

  const loadProjects = useCallback(async () => {
    const found = await request<Project[]>("/api/v1/projects");
    setProjects(found);
    setProjectId(current => found.some(project => project.id === current) ? current : (found[0]?.id ?? ""));
  }, [request]);
  const loadJobs = useCallback(async () => {
    if (!projectId) return;
    const page = await request<JobPage>("/api/v1/projects/" + projectId + "/jobs?limit=100");
    setJobs(page.items);
    setLastUpdated(new Date());
  }, [projectId, request]);
  const loadKeys = useCallback(async () => {
    if (projectId) setKeys(await request<ApiKey[]>("/api/v1/projects/" + projectId + "/api-keys"));
  }, [projectId, request]);
  const loadSourceAssets = useCallback(async () => {
    if (projectId) setSourceAssets(await request<FileAsset[]>("/api/v1/projects/" + projectId + "/files?kind=SOURCE_CSV"));
  }, [projectId, request]);

  useEffect(() => {
    if (session) void loadProjects().catch(cause => {
      if (!(cause instanceof SupersededRequestError)) setError(errorMessage(cause, "Could not load projects."));
    });
  }, [session, loadProjects]);
  useEffect(() => {
    if (!projectId) return;
    void loadJobs().catch(cause => {
      if (!(cause instanceof SupersededRequestError)) setError(errorMessage(cause, "Could not load jobs."));
    });
    void loadKeys().catch(cause => {
      if (!(cause instanceof SupersededRequestError)) setError(errorMessage(cause, "Could not load API keys."));
    });
    void loadSourceAssets().catch(cause => {
      if (!(cause instanceof SupersededRequestError)) setError(errorMessage(cause, "Could not load uploaded CSV files."));
    });
    setSelectedSourceAssetId("");
    setSourceFile(null);
    setSourceFileError(null);
    setSubmitError(null);
    if (sourceFileInput.current) sourceFileInput.current.value = "";
  }, [projectId, loadJobs, loadKeys, loadSourceAssets]);
  useEffect(() => {
    const timer = window.setInterval(() => setScheduleMinimum(nextScheduleMinimum()), 30_000);
    return () => window.clearInterval(timer);
  }, []);
  useEffect(() => {
    if (!notice) return;
    const timer = window.setTimeout(() => setNotice(null), 6_000);
    return () => window.clearTimeout(timer);
  }, [notice]);
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    localStorage.setItem("job-platform-theme", theme);
  }, [theme]);
  useEffect(() => {
    if (!keyPendingRevocation) return;
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !busy) setKeyPendingRevocation(null);
    };
    window.addEventListener("keydown", closeOnEscape);
    return () => window.removeEventListener("keydown", closeOnEscape);
  }, [busy, keyPendingRevocation]);
  useEffect(() => {
    const handlePopState = () => setRoute(currentRoute());
    window.addEventListener("popstate", handlePopState);
    return () => window.removeEventListener("popstate", handlePopState);
  }, []);

  const activeProject = useMemo(() => projects.find(project => project.id === projectId), [projects, projectId]);
  const selectedSourceAsset = useMemo(() => sourceAssets.find(asset => asset.id === selectedSourceAssetId), [selectedSourceAssetId, sourceAssets]);
  const activeRouteJob = useMemo(() => route.jobId
    ? jobs.find(job => job.id === route.jobId) ?? (routedJob?.id === route.jobId ? routedJob : null)
    : null, [jobs, route.jobId, routedJob]);
  const latestRouteAttempt = useMemo(() => activeRouteJob?.runs.flatMap(run => run.executions)
    .filter(attempt => attempt.result || attempt.resultRef)
    .at(-1) ?? null, [activeRouteJob]);
  const fileResult = useMemo(() => {
    const result = latestRouteAttempt?.result;
    return result?.type === "PROCESS_FILE" ? result : null;
  }, [latestRouteAttempt]);
  const cleanedCsvAssetId = stringResult(fileResult ?? undefined, "cleanedAssetId");
  const errorCsvAssetId = stringResult(fileResult ?? undefined, "errorAssetId");
  const csvPreviewAssetKey = route.page === "result" && projectId && cleanedCsvAssetId && errorCsvAssetId
    ? [projectId, cleanedCsvAssetId, errorCsvAssetId].join(":")
    : "";
  const summary = useMemo(() => ({
    completed: jobs.filter(job => job.status === "COMPLETED").length,
    inProgress: jobs.filter(job => ["PENDING", "QUEUED", "RUNNING", "RETRY_WAIT"].includes(job.status)).length,
    failed: jobs.filter(job => ["FAILED", "CANCELLED"].includes(job.status)).length
  }), [jobs]);
  const hasActiveJobs = useMemo(() => jobs.some(job => ["PENDING", "QUEUED", "RUNNING", "RETRY_WAIT"].includes(job.status)), [jobs]);
  const jobPollInterval = hasActiveJobs ? 5_000 : 15_000;
  const filteredJobs = useMemo(() => jobs.filter(job =>
    (statusFilter === "ALL" || job.status === statusFilter)
    && (typeFilter === "ALL" || job.jobType === typeFilter)
    && (priorityFilter === "ALL" || job.priority === priorityFilter)
  ), [jobs, priorityFilter, statusFilter, typeFilter]);
  const hasActiveFilters = statusFilter !== "ALL" || typeFilter !== "ALL" || priorityFilter !== "ALL";
  const payload: Record<string, unknown> | null = useMemo(() => {
    if (jobType === "GENERATE_REPORT") return { template, periodStart, periodEnd };
    if (jobType === "SEND_EMAIL") {
      try { return { template, recipient, variables: JSON.parse(variables) as Record<string, string> }; }
      catch { return null; }
    }
    if (jobType === "PROCESS_FILE") return null;
    return { channel: "EMAIL", recipient, template };
  }, [jobType, template, periodStart, periodEnd, recipient, variables]);
  const apiExample = useMemo(() => {
    const apiBaseUrl = window.location.origin + "/api/v1";
    const body = apiExampleType === "PROCESS_FILE"
      ? {
          jobType: "PROCESS_FILE",
          priority: "DEFAULT",
          payload: { sourceAssetId: "<uploaded-csv-asset-id>", operation: "CSV_NORMALIZE" }
        }
      : {
          jobType: "GENERATE_REPORT",
          priority: "DEFAULT",
          scheduledAt: new Date(Date.now() + 15 * 60_000).toISOString(),
          payload: { template: "SALES_SUMMARY", periodStart: "2026-09-01", periodEnd: "2026-09-27" }
        };
    const endpoint = apiBaseUrl + "/projects/" + projectId + "/jobs";
    const requestJson = JSON.stringify(body, null, 2);
    return {
      apiBaseUrl,
      endpoint,
      requestJson,
      command: [
        "curl --request POST '" + endpoint + "' \\",
        "  --header 'X-API-Key: $JOB_PLATFORM_API_KEY' \\",
        "  --header \"Idempotency-Key: $(uuidgen)\" \\",
        "  --header 'Content-Type: application/json' \\",
        "  --data @job.json"
      ].join("\n")
    };
  }, [apiExampleType, projectId]);
  const activeCsvPreview = csvPreviews[csvReviewTab];
  const csvReviewMetadata: Record<CsvReviewTab, { label: string; description: string }> = {
    valid: { label: "Valid rows", description: "Rows retained in the cleaned CSV" },
    rejected: { label: "Rejected rows", description: "Rows with validation issues" },
    duplicates: { label: "Duplicates removed", description: "Exact duplicate rows removed during cleanup" }
  };
  const activeCsvReview = csvReviewMetadata[csvReviewTab];
  const currentResultMessage = activeRouteJob && latestRouteAttempt
    ? resultMessage(activeRouteJob.jobType, Boolean(artifactReference(latestRouteAttempt)))
    : null;
  const activeRouteElapsedTime = activeRouteJob
    ? elapsedTime(activeRouteJob.scheduledAt ?? activeRouteJob.createdAt, activeRouteJob.completedAt ?? latestRouteAttempt?.finishedAt)
    : null;

  useEffect(() => {
    if (!projectId) return;
    let timer: number | undefined;
    let active = true;
    const refreshWhenVisible = () => {
      if (document.visibilityState === "visible") void loadJobs().catch(() => undefined);
    };
    const scheduleNext = () => {
      timer = window.setTimeout(async () => {
        if (!active) return;
        if (document.visibilityState === "visible") await loadJobs().catch(() => undefined);
        if (active) scheduleNext();
      }, jobPollInterval);
    };
    scheduleNext();
    document.addEventListener("visibilitychange", refreshWhenVisible);
    return () => { active = false; if (timer) window.clearTimeout(timer); document.removeEventListener("visibilitychange", refreshWhenVisible); };
  }, [jobPollInterval, loadJobs, projectId]);

  useEffect(() => {
    if (!projectId || !route.jobId) { setRoutedJob(null); return; }
    const inList = jobs.find(job => job.id === route.jobId);
    if (inList) { setRoutedJob(inList); return; }
    void request<Job>("/api/v1/projects/" + projectId + "/jobs/" + route.jobId)
      .then(setRoutedJob)
      .catch(cause => {
        if (!(cause instanceof SupersededRequestError)) setError(errorMessage(cause, "Could not load this job."));
      });
  }, [jobs, projectId, request, route.jobId]);

  const previousCsvPreviewAssetKey = useRef<string | null>(null);
  useEffect(() => {
    if (previousCsvPreviewAssetKey.current === csvPreviewAssetKey) return;
    previousCsvPreviewAssetKey.current = csvPreviewAssetKey;
    setCsvReviewTab("valid");
    setCsvPreviews({});
    setCsvPreviewError(null);
    setCsvPreviewLoading(Boolean(csvPreviewAssetKey));
  }, [csvPreviewAssetKey]);
  useEffect(() => {
    if (!csvPreviewAssetKey || activeCsvPreview) return;
    let active = true;
    setCsvPreviewError(null);
    setCsvPreviewLoading(true);
    const preview = csvReviewTab === "valid"
      ? request<CsvPreview>("/api/v1/projects/" + projectId + "/files/" + cleanedCsvAssetId + "/preview?limit=12")
      : request<CsvPreview>("/api/v1/projects/" + projectId + "/files/" + errorCsvAssetId + "/preview?limit=12" + (csvReviewTab === "duplicates" ? "&reason=DUPLICATE_REMOVED" : "&excludeReason=DUPLICATE_REMOVED"));
    void preview.then(result => {
      if (active) setCsvPreviews(current => ({ ...current, [csvReviewTab]: result }));
    }).catch(cause => {
      if (active && !(cause instanceof SupersededRequestError)) setCsvPreviewError(errorMessage(cause, "Could not load CSV row samples."));
    }).finally(() => {
      if (active) setCsvPreviewLoading(false);
    });
    return () => { active = false; };
  }, [activeCsvPreview, cleanedCsvAssetId, csvPreviewAssetKey, csvReviewTab, errorCsvAssetId, projectId, request]);

  function navigate(path: string) {
    window.history.pushState({}, "", path);
    setRoute(currentRoute(path));
    window.scrollTo({ top: 0, behavior: "smooth" });
  }

  async function authenticate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); setError(null);
    try {
      const body = mode === "register" ? { email, password, projectName } : { email, password };
      const next = await request<Session>("/api/v1/auth/" + (mode === "register" ? "register" : "login"), { method: "POST", body: JSON.stringify(body) });
      beginSession(next); setNotice("You are signed in.");
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
  function selectSourceFile(file: File | null) {
    setSourceFile(file);
    if (file) setSelectedSourceAssetId("");
    setSubmitError(null);
    setSourceFileError(file && file.size > maximumCsvUploadBytes
      ? `“${file.name}” is larger than the 10 MiB CSV upload limit. Choose a smaller file.`
      : null);
  }
  function stopSubmission(message: string) {
    setSubmitError(message);
    setBusy(false);
  }
  async function submitJob(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); setError(null); setSubmitError(null); setNotice(null);
    if (!projectId) { stopSubmission("Choose a project before queuing a job."); return; }
    if (jobType !== "PROCESS_FILE" && !payload) { stopSubmission("Email variables must be valid JSON."); return; }
    if (jobType === "GENERATE_REPORT" && periodStart > periodEnd) { stopSubmission("Report period end must be on or after the start date."); return; }
    if (scheduleLater && (!scheduledAt || new Date(scheduledAt).getTime() < Date.now() + 10_000)) {
      stopSubmission("Choose a time at least 10 seconds in the future."); return;
    }
    try {
      let requestPayload = payload;
      if (jobType === "PROCESS_FILE") {
        if (sourceFileError) { stopSubmission(sourceFileError); return; }
        let sourceAssetId = selectedSourceAssetId;
        if (sourceFile) {
          const uploaded = await uploadSourceFile(sourceFile);
          sourceAssetId = uploaded.id;
          setSourceAssets(current => [uploaded, ...current.filter(asset => asset.id !== uploaded.id)]);
          setSelectedSourceAssetId(uploaded.id);
        }
        if (!sourceAssetId) { stopSubmission("Choose an uploaded CSV or select a new one before queuing this job."); return; }
        requestPayload = { sourceAssetId, operation };
      }
      const accepted = await request<{ jobId: string }>("/api/v1/projects/" + projectId + "/jobs", {
        method: "POST",
        headers: { "Idempotency-Key": crypto.randomUUID() },
        body: JSON.stringify({ jobType, payload: requestPayload, priority, ...(scheduleLater ? { scheduledAt: iso(scheduledAt) } : {}) })
      });
      setScheduledAt(""); setScheduleLater(false); setSourceFile(null);
      if (sourceFileInput.current) sourceFileInput.current.value = "";
      setNotice(jobLabel(jobType) + " job accepted · " + accepted.jobId.slice(0, 8) + "…");
      await loadJobs();
    } catch (cause) { setSubmitError(errorMessage(cause, "Job submission failed.")); }
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
  async function revokeKey() {
    const key = keyPendingRevocation;
    if (!key) return;
    setBusy(true); setError(null);
    try {
      await request<void>("/api/v1/api-keys/" + key.id + "/revoke", { method: "POST" });
      setCreatedKey(null); setKeyPendingRevocation(null); await loadKeys(); setNotice("API key revoked.");
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
    if (job.runs.length >= 3) {
      setError("This job has used its two manual retry runs. Submit a new job after fixing the source or inputs.");
      return;
    }
    setBusy(true); setRetryingJobId(job.id); setError(null);
    try {
      await request<{ runId: string }>("/api/v1/projects/" + projectId + "/jobs/" + job.id + "/retry", { method: "POST", headers: { "Idempotency-Key": crypto.randomUUID() } });
      await loadJobs(); setNotice("A new retry run was accepted.");
    } catch (cause) { setError(errorMessage(cause, "Could not start a manual retry.")); }
    finally { setRetryingJobId(null); setBusy(false); }
  }
  async function copy(value: string, label: string) {
    try { await navigator.clipboard.writeText(value); setNotice(label + " copied to clipboard."); }
    catch { setError("Could not copy the " + label.toLowerCase() + ". Select and copy it manually."); }
  }
  async function uploadSourceFile(file: File): Promise<FileAsset> {
    const uploadSession = sessionRef.current;
    const uploadGeneration = sessionGeneration.current;
    if (!uploadSession) throw new Error("Your session has expired. Sign in again.");
    const data = new FormData();
    data.append("file", file);
    const response = await fetch("/api/v1/projects/" + projectId + "/files", {
      method: "POST", headers: { Authorization: "Bearer " + uploadSession.accessToken }, body: data
    });
    const body = await response.json().catch(() => null);
    if (uploadGeneration !== sessionGeneration.current) throw new SupersededRequestError();
    if (response.status === 401) {
      endSession();
    }
    if (!response.ok) {
      throw new Error(response.status === 413 ? "CSV uploads must be 10 MiB or smaller." : errorMessage(body, "The source file could not be uploaded."));
    }
    return body as FileAsset;
  }
  async function uploadSourceAsset(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!sourceFile) { setSourceFileError("Choose a CSV file before uploading."); return; }
    if (sourceFileError) return;
    setLibraryUploadPending(true); setError(null); setNotice(null);
    try {
      const uploaded = await uploadSourceFile(sourceFile);
      setSourceAssets(current => [uploaded, ...current.filter(asset => asset.id !== uploaded.id)]);
      setSelectedSourceAssetId(uploaded.id);
      setSourceFile(null);
      if (sourceFileInput.current) sourceFileInput.current.value = "";
      setNotice("CSV uploaded and ready to reuse in a job.");
    } catch (cause) { setSourceFileError(errorMessage(cause, "The source file could not be uploaded.")); }
    finally { setLibraryUploadPending(false); }
  }
  function startProcessingAsset(asset: FileAsset) {
    setJobType("PROCESS_FILE");
    setSelectedSourceAssetId(asset.id);
    setSourceFile(null);
    setSourceFileError(null);
    navigate("/");
    window.setTimeout(() => document.getElementById("new-job")?.focus(), 200);
  }
  async function downloadPrivateFile(reference: string, filename: string) {
    const downloadSession = sessionRef.current;
    if (!downloadSession) return;
    setBusy(true); setError(null);
    try {
      const response = await fetch(reference, { headers: { Authorization: "Bearer " + downloadSession.accessToken } });
      if (!response.ok) throw new Error("The result file is no longer available.");
      const blob = await response.blob();
      const objectUrl = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = objectUrl; link.download = filename; link.click();
      URL.revokeObjectURL(objectUrl);
      setNotice("Result downloaded.");
    } catch (cause) { setError(errorMessage(cause, "Could not download the result.")); }
    finally { setBusy(false); }
  }
  async function downloadArtifact(reference: string) { await downloadPrivateFile(reference, "job-result.json"); }
  async function downloadFileAsset(assetId: string, filename: string) {
    await downloadPrivateFile("/api/v1/projects/" + projectId + "/files/" + assetId + "/download", filename);
  }
  async function refreshJobs() {
    setRefreshing(true); setError(null);
    try { await loadJobs(); }
    catch (cause) { setError(errorMessage(cause, "Could not refresh jobs.")); }
    finally { setRefreshing(false); }
  }
  function clearFilters() {
    setStatusFilter("ALL"); setTypeFilter("ALL"); setPriorityFilter("ALL");
  }

  if (!session) return <main className="auth-shell">
    <section className="auth-card surface">
      <div className="auth-brand"><div className="brand-mark" aria-hidden="true">↗</div><span>Distributed Job Platform</span></div>
      <div className="auth-heading">
        <p className="eyebrow">{mode === "login" ? "Welcome back" : "New workspace"}</p>
        <h1>{mode === "login" ? "Sign in to your workspace" : "Create your workspace"}</h1>
        <p className="lead">{mode === "login" ? "Pick up where you left off and follow every job from one place." : "Set up a project, queue work safely, and follow every execution."}</p>
      </div>
      <form className="auth-form" onSubmit={authenticate}>
        {mode === "register" && <label>First project name<input value={projectName} onChange={event => setProjectName(event.target.value)} autoComplete="organization" minLength={3} required /></label>}
        <label>Email<input value={email} onChange={event => setEmail(event.target.value)} autoComplete="email" type="email" required /></label>
        <label>Password<input value={password} onChange={event => setPassword(event.target.value)} autoComplete={mode === "login" ? "current-password" : "new-password"} type="password" minLength={12} required /></label>
        <button className="primary" disabled={busy}>{busy ? "Please wait…" : mode === "login" ? "Sign in" : "Create workspace"}</button>
      </form>
      <div className="auth-switch"><span>{mode === "login" ? "New to the platform?" : "Already have an account?"}</span><button className="text-button auth-switch-button" type="button" onClick={() => { setMode(mode === "login" ? "register" : "login"); setError(null); }}>{mode === "login" ? "Create a workspace" : "Sign in instead"}</button></div>
      {error && <p className="error inline-message" role="alert">{error}</p>}
    </section>
  </main>;

  return <main className="dashboard" data-retrying={retryingJobId ?? undefined}>
    <header className="app-header">
      <div className="header-title"><div className="brand-mark" aria-hidden="true">↗</div><div><p className="eyebrow">Distributed Job Platform</p><h1>Job control centre</h1></div></div>
      <div className="header-actions"><span className="live-indicator"><i />Live updates</span><button className="secondary theme-toggle" onClick={() => setTheme(current => current === "dark" ? "light" : "dark")} aria-label={theme === "dark" ? "Switch to light mode" : "Switch to dark mode"}>{theme === "dark" ? "☀ Light" : "◐ Dark"}</button><button className="secondary" onClick={endSession}>Sign out</button></div>
    </header>
    <nav className="app-nav" aria-label="Workspace pages">
      <button className={route.page === "dashboard" ? "nav-link active" : "nav-link"} aria-current={route.page === "dashboard" ? "page" : undefined} onClick={() => navigate("/")}>Dashboard</button>
      <button className={route.page === "files" ? "nav-link active" : "nav-link"} aria-current={route.page === "files" ? "page" : undefined} onClick={() => navigate("/files")}>Files</button>
      <button className={route.page === "api" ? "nav-link active" : "nav-link"} aria-current={route.page === "api" ? "page" : undefined} onClick={() => navigate("/api")}>API setup</button>
      {route.page !== "dashboard" && route.page !== "files" && route.page !== "api" && <span className="route-crumb" aria-current="page">{routeTitle(route)}</span>}
    </nav>
    {(error || notice) && <p className={error ? "error banner" : "success banner"} role={error ? "alert" : "status"}>{error ?? notice}</p>}
    <section className="project-bar surface">
      <div className="project-selector">
        <label>Active project<select value={projectId} onChange={event => setProjectId(event.target.value)}>{projects.map(project => <option key={project.id} value={project.id}>{project.name}</option>)}</select></label>
        {activeProject && <div className="project-id"><span>Project ID</span><code title={activeProject.id}>{activeProject.id}</code><button className="icon-button" title="Copy project ID" aria-label="Copy project ID" onClick={() => void copy(activeProject.id, "Project ID")}>⧉</button></div>}
      </div>
      <form onSubmit={createProject} className="new-project"><input value={projectName} onChange={event => setProjectName(event.target.value)} placeholder="New project name" minLength={3} aria-label="New project name" required /><button className="secondary" disabled={busy}>Add project</button></form>
    </section>
    {projectId && route.page === "files" && <section className="route-page surface" aria-labelledby="files-title">
      <div className="route-heading"><div><p className="eyebrow">Project library</p><h2 id="files-title">CSV files</h2><p>Upload a source once, then reuse it securely in as many file-processing jobs as you need.</p></div><span className="asset-count">{sourceAssets.length} saved</span></div>
      <div className="file-library-grid">
        <form className="file-upload-card" onSubmit={uploadSourceAsset}>
          <div><strong>Upload a source CSV</strong><p>CSV only · 10 MiB maximum · private to this project.</p></div>
          <input className="visually-hidden" ref={sourceFileInput} id="library-source-csv-file" type="file" accept=".csv,text/csv" onChange={event => selectSourceFile(event.target.files?.[0] ?? null)} />
          <label className={"csv-dropzone" + (sourceFile ? " has-file" : "")} htmlFor="library-source-csv-file"><span className="upload-glyph" aria-hidden="true">↑</span><span><strong>{sourceFile ? sourceFile.name : "Choose a CSV file"}</strong><small>{sourceFile ? Math.ceil(sourceFile.size / 1024) + " KB selected" : "Select a local .csv file"}</small></span><span className="choose-file">Browse</span></label>
          {sourceFileError && <p className="source-validation error" role="alert">{sourceFileError}</p>}
          <button className="primary" disabled={libraryUploadPending || Boolean(sourceFileError)}><span className="button-content">{libraryUploadPending && <i className="spinner" aria-hidden="true" />}{libraryUploadPending ? "Uploading…" : "Upload CSV"}</span></button>
        </form>
        <aside className="route-tip"><strong>Private project assets</strong><p>Files are stored once and linked to jobs by ID. Every download still checks the signed-in user and active project.</p></aside>
      </div>
      <div className="asset-library" aria-live="polite">
        {sourceAssets.length === 0 ? <div className="empty-state"><div aria-hidden="true">⌁</div><h3>No CSV files yet</h3><p>Upload a source file here, then use it in a processing job.</p></div> : sourceAssets.map(asset => <article key={asset.id} className="asset-card"><div className="asset-icon" aria-hidden="true">CSV</div><div className="asset-info"><strong>{asset.filename}</strong><small>{asset.rowCount ?? 0} rows · {Math.ceil(asset.sizeBytes / 1024)} KB · uploaded {display(asset.createdAt)}</small><code>{asset.id}</code></div><div className="asset-actions"><button className="secondary" onClick={() => void downloadFileAsset(asset.id, asset.filename)} disabled={busy}>Download</button><button onClick={() => startProcessingAsset(asset)}>Process file</button></div></article>)}
      </div>
    </section>}
    {projectId && route.page === "api" && <section className="route-page surface api-setup-page" aria-labelledby="api-setup-title">
      <div className="route-heading">
        <div><p className="eyebrow">Developer quick start</p><h2 id="api-setup-title">Schedule jobs from your application</h2><p>Create a project key once, then use the generated request below from a server-side integration.</p></div>
        <span className="asset-count">Project-scoped access</span>
      </div>
      <div className="api-guide">
        <section className="api-step-card">
          <div className="api-step-heading"><span className="step-number">1</span><div><h3>Create a project key</h3><p>Name it for the integration using it—for example, “billing-service” or “CI”.</p></div></div>
          {createdKey && <div className="secret api-secret"><div className="secret-heading"><span aria-hidden="true">◆</span><div><strong>Copy this key now</strong><p>It is shown only once and cannot be recovered later.</p></div></div><code>{createdKey.plaintextKey}</code><div className="secret-actions"><button className="secondary" onClick={() => void copy(createdKey.plaintextKey, "API key")}>Copy key</button><button className="text-button" onClick={() => setCreatedKey(null)}>Hide key</button></div></div>}
          <form className="new-key api-key-form" onSubmit={createKey}><label className="visually-hidden" htmlFor="api-key-name">API key client name</label><input id="api-key-name" value={keyName} onChange={event => setKeyName(event.target.value)} placeholder="Integration name" minLength={3} required /><button disabled={busy}>Create key</button></form>
          <p className="api-security-note">Keep the key in your server-side secret manager. Never place it in browser code, a mobile app, or a public repository.</p>
        </section>
        <section className="api-step-card">
          <div className="api-step-heading"><span className="step-number">2</span><div><h3>Set your connection values</h3><p>The project ID is already included in the example and keeps API access isolated to this workspace.</p></div></div>
          <dl className="connection-values"><div><dt>API base URL</dt><dd><code>{apiExample.apiBaseUrl}</code><button className="icon-button" type="button" title="Copy API base URL" aria-label="Copy API base URL" onClick={() => void copy(apiExample.apiBaseUrl, "API base URL")}>⧉</button></dd></div><div><dt>Project ID</dt><dd><code>{projectId}</code><button className="icon-button" type="button" title="Copy project ID" aria-label="Copy project ID" onClick={() => void copy(projectId, "Project ID")}>⧉</button></dd></div><div><dt>Authentication</dt><dd><code>X-API-Key: $JOB_PLATFORM_API_KEY</code></dd></div></dl>
          <div className="code-block"><div><strong>Environment variable</strong><button className="text-button compact" type="button" onClick={() => void copy("export JOB_PLATFORM_API_KEY='paste-key-here'", "Environment variable")}>Copy</button></div><pre><code>export JOB_PLATFORM_API_KEY='paste-key-here'</code></pre></div>
        </section>
        <section className="api-step-card api-request-card">
          <div className="api-step-heading"><span className="step-number">3</span><div><h3>Send a job request</h3><p>Use a new idempotency key for each intended job. Retrying the same request safely returns the original job.</p></div></div>
          <div className="example-toggle" role="group" aria-label="Request example type"><button type="button" className={apiExampleType === "GENERATE_REPORT" ? "active" : ""} onClick={() => setApiExampleType("GENERATE_REPORT")}>Generate report</button><button type="button" className={apiExampleType === "PROCESS_FILE" ? "active" : ""} onClick={() => setApiExampleType("PROCESS_FILE")}>Process uploaded CSV</button></div>
          <div className="code-block"><div><strong>job.json</strong><button className="text-button compact" type="button" onClick={() => void copy(apiExample.requestJson, "Job JSON")}>Copy</button></div><pre><code>{apiExample.requestJson}</code></pre></div>
          <div className="code-block"><div><strong>Request</strong><button className="text-button compact" type="button" onClick={() => void copy(apiExample.command, "cURL request")}>Copy</button></div><pre><code>{apiExample.command}</code></pre></div>
        </section>
        <section className="api-step-card api-followup-card">
          <div className="api-step-heading"><span className="step-number">4</span><div><h3>Track the accepted job</h3><p>A successful request returns a job ID and status URL immediately. Poll that URL or use the dashboard to follow its attempts and result.</p></div></div>
          <div className="code-block"><div><strong>Response shape</strong><button className="text-button compact" type="button" onClick={() => void copy('{\n  "jobId": "<job-id>",\n  "status": "QUEUED",\n  "statusUrl": "<status-url>"\n}', "Response example")}>Copy</button></div><pre><code>{'{\n  "jobId": "<job-id>",\n  "status": "QUEUED",\n  "statusUrl": "<status-url>"\n}'}</code></pre></div>
          <p className="api-security-note">For file processing, first upload the CSV through the authenticated Files API, then place the returned asset ID in <code>sourceAssetId</code>.</p>
        </section>
      </div>
    </section>}
    {projectId && (route.page === "job" || route.page === "result") && <section className="route-page surface job-route-page" aria-labelledby="route-job-title">
      {!activeRouteJob ? <div className="empty-state"><div aria-hidden="true">⌕</div><h2 id="route-job-title">Job not found</h2><p>The job may belong to another project, or it may no longer be available.</p><button className="secondary" onClick={() => navigate("/")}>Back to dashboard</button></div> : route.page === "job" ? <>
        <div className="route-heading"><div><p className="eyebrow">Job detail</p><h2 id="route-job-title">{jobLabel(activeRouteJob.jobType)}</h2><p>Created {display(activeRouteJob.createdAt)} · {activeRouteJob.priority} priority</p></div><span className={statusClass(activeRouteJob.status)}>{activeRouteJob.status.replace("_", " ")}</span></div>
        <div className="job-detail-overview">
          <div className="detail-facts"><span><b>Job ID</b><code>{activeRouteJob.id}</code></span><span><b>Scheduled</b>{activeRouteJob.scheduledAt ? display(activeRouteJob.scheduledAt) : "Immediately"}</span><span><b>Finished</b>{display(activeRouteJob.completedAt)}</span><span><b>Elapsed time</b>{activeRouteElapsedTime ?? "—"}</span></div>
          <div className="detail-actions">{latestRouteAttempt && <button onClick={() => navigate("/jobs/" + activeRouteJob.id + "/result")}>View result</button>}{activeRouteJob.status === "FAILED" && <button className="secondary" onClick={() => void retry(activeRouteJob)} disabled={busy}><span className="button-content">{retryingJobId === activeRouteJob.id && <i className="spinner" aria-hidden="true" />}{retryingJobId === activeRouteJob.id ? "Retrying…" : "Retry job"}</span></button>}{["PENDING", "QUEUED"].includes(activeRouteJob.status) && <button className="danger" onClick={() => void cancel(activeRouteJob)} disabled={busy}>Cancel job</button>}<button className="secondary quiet-button detail-back" onClick={() => navigate("/")}>Back to dashboard</button></div>
        </div>
        <section className="attempt-timeline" aria-labelledby="attempts-title"><div className="attempt-timeline-heading"><div><h3 id="attempts-title">Execution history</h3><p>Each run is retained here for troubleshooting and audit.</p></div><span>{activeRouteJob.runs.length} {activeRouteJob.runs.length === 1 ? "run" : "runs"}</span></div>{activeRouteJob.runs.map(run => <article className="run-detail" key={run.id}><div className="run-detail-heading"><div><strong>Run {run.runNumber}</strong>{run.status !== activeRouteJob.status && <span className={statusClass(run.status)}>{run.status}</span>}<small>{run.status === "COMPLETED" ? "Finished after " + run.attemptCount + " " + (run.attemptCount === 1 ? "attempt" : "attempts") : run.attemptCount + "/" + run.maxAttempts + " attempts"}</small></div></div>{run.executions.length === 0 ? <p className="muted">Waiting for a worker to claim this run.</p> : run.executions.map(attempt => <div className="attempt-detail" key={attempt.id}><strong>Attempt {attempt.attemptNumber}</strong>{attempt.status !== "COMPLETED" && <span className={statusClass(attempt.status)}>{attempt.status}</span>}<span>{attempt.status === "COMPLETED" ? "Finished successfully" : "Finished with " + attempt.status.toLowerCase().replace("_", " ")}</span><time dateTime={attempt.finishedAt ?? attempt.startedAt}>{display(attempt.finishedAt ?? attempt.startedAt)}</time>{attempt.errorCode && <span className="error">{attempt.errorCode}</span>}</div>)}</article>)}</section>
      </> : <>
        <div className="route-heading"><div><p className="eyebrow">Job result</p><h2 id="route-job-title">{jobLabel(activeRouteJob.jobType)}</h2><p>Review the final outcome for this execution.</p></div><span className={statusClass(activeRouteJob.status)}>{activeRouteJob.status.replace("_", " ")}</span></div>
        {!latestRouteAttempt ? <div className="empty-state"><div aria-hidden="true">◌</div><h3>No result yet</h3><p>This job has not completed a successful attempt. Refresh or return to its details to track progress.</p><button className="secondary" onClick={() => navigate("/jobs/" + activeRouteJob.id)}>View job details</button></div> : <div className="result-view">
          <p className="result-timing"><span>Finished {display(latestRouteAttempt.finishedAt)}</span>{activeRouteElapsedTime && <span>Elapsed time {activeRouteElapsedTime}</span>}</p>
          {fileResult ? <section className="file-review" aria-label="CSV processing review">
            <div className="file-review-header">
              <div><h3>Review the processed rows</h3><p>Inspect a small, safe sample before downloading the full output files.</p></div>
              <div className="file-review-actions">
                {stringResult(fileResult, "cleanedAssetId") && <button onClick={() => void downloadFileAsset(stringResult(fileResult, "cleanedAssetId")!, "cleaned.csv")} disabled={busy}>Download cleaned CSV</button>}
                {stringResult(fileResult, "errorAssetId") && <button className="secondary" onClick={() => void downloadFileAsset(stringResult(fileResult, "errorAssetId")!, "errors.csv")} disabled={busy}>Download error report</button>}
              </div>
            </div>
            <div className="file-result result-metrics">
              <div><strong>{numberResult(fileResult, "totalRows")}</strong><small>Total rows</small></div>
              <div><strong>{numberResult(fileResult, "validRows")}</strong><small>Valid</small></div>
              <div><strong>{numberResult(fileResult, "rejectedRows")}</strong><small>Rejected</small></div>
              <div><strong>{numberResult(fileResult, "duplicatesRemoved")}</strong><small>Duplicates removed</small></div>
            </div>
            <div className="csv-review-tabs" role="tablist" aria-label="Processed CSV row samples">
              {(Object.keys(csvReviewMetadata) as CsvReviewTab[]).map(tab => <button key={tab} type="button" role="tab" aria-selected={csvReviewTab === tab} className={csvReviewTab === tab ? "active" : ""} onClick={() => setCsvReviewTab(tab)}>{csvReviewMetadata[tab].label}</button>)}
            </div>
            <section className="csv-preview" aria-live="polite">
              <div className="csv-preview-heading"><div><h4>{activeCsvReview.label}</h4><p>{activeCsvReview.description}</p></div><span>{activeCsvPreview?.hasMore ? "First 12 rows" : "All matching rows"}</span></div>
              {csvPreviewLoading ? <div className="preview-state"><i className="spinner" aria-hidden="true" />Loading row sample…</div> : csvPreviewError ? <div className="preview-state error" role="alert">{csvPreviewError}</div> : !activeCsvPreview || activeCsvPreview.rows.length === 0 ? <div className="preview-state">No {activeCsvReview.label.toLowerCase()} in this result.</div> : <><div className="csv-table-wrap"><table><thead><tr>{activeCsvPreview.header.map(header => <th key={header} scope="col">{header}</th>)}</tr></thead><tbody>{activeCsvPreview.rows.map((row, rowIndex) => <tr key={rowIndex + "-" + row.join("|")}>{activeCsvPreview.header.map((_, columnIndex) => <td key={columnIndex} title={row[columnIndex] ?? ""}>{row[columnIndex] ?? ""}</td>)}</tr>)}</tbody></table></div><p className="preview-footnote">{activeCsvPreview.hasMore ? "Showing the first 12 matching rows. Download the full CSV to review every row." : "This output has no additional matching rows."}</p></>}
            </section>
          </section> : <section className="generic-result">
            <span className="outcome-icon" aria-hidden="true">✓</span>
            <div><p className="eyebrow">{currentResultMessage?.eyebrow}</p><h3>{currentResultMessage?.title}</h3><p>{currentResultMessage?.description}</p></div>
            {artifactReference(latestRouteAttempt) && <button onClick={() => void downloadArtifact(artifactReference(latestRouteAttempt)!)} disabled={busy}>Download result</button>}
          </section>}
        </div>}
        <div className="route-actions"><button className="secondary" onClick={() => navigate("/jobs/" + activeRouteJob.id)}>View job details</button><button className="secondary quiet-button" onClick={() => navigate("/")}>Return to dashboard</button></div>
      </>}
    </section>}
    {projectId && route.page === "dashboard" && <>
      <section className="summary-grid" aria-label="Recent job summary">
        <article className="summary-card surface"><span>Recent jobs</span><strong>{jobs.length}</strong><small>Latest 100 jobs</small></article>
        <article className="summary-card surface in-progress"><span>In progress</span><strong>{summary.inProgress}</strong><small>Queued, running, or scheduled</small></article>
        <article className="summary-card surface completed"><span>Completed</span><strong>{summary.completed}</strong><small>Recent successful work</small></article>
        <article className="summary-card surface failed"><span>Needs attention</span><strong>{summary.failed}</strong><small>Failed or cancelled</small></article>
      </section>
      <div className="workspace-grid">
        <section className="panel surface submit">
          <div className="panel-heading"><div><p className="eyebrow">New work</p><h2 id="new-job" tabIndex={-1}>Queue a job</h2></div><span>Durably accepted</span></div>
          <form onSubmit={submitJob}>
            <label>Job type<select value={jobType} onChange={event => selectJobType(event.target.value)}><option value="GENERATE_REPORT">Generate report</option><option value="SEND_EMAIL">Send email</option><option value="PROCESS_FILE">Process file</option><option value="SEND_NOTIFICATION">Send notification</option></select><small>{jobDescriptions[jobType]}</small></label>
            <div className="two-columns"><label>Priority<select value={priority} onChange={event => setPriority(event.target.value)}><option>HIGH</option><option>DEFAULT</option><option>LOW</option></select></label><label className="schedule-toggle"><input type="checkbox" checked={scheduleLater} onChange={event => { setScheduleLater(event.target.checked); if (!event.target.checked) setScheduledAt(""); }} />Schedule for later</label></div>
            {scheduleLater && <label className="schedule-field">Run once at<input type="datetime-local" value={scheduledAt} min={scheduleMinimum} onChange={event => setScheduledAt(event.target.value)} required /><small>Local time. The earliest available time is safely in the future.</small></label>}
            {jobType === "GENERATE_REPORT" && <><label>Report template<select value={template} onChange={event => setTemplate(event.target.value)}><option>SALES_SUMMARY</option><option>JOB_AUDIT</option></select></label><div className="two-columns"><label>Period start<input type="date" value={periodStart} max={periodEnd || undefined} onChange={event => setPeriodStart(event.target.value)} required /></label><label>Period end<input type="date" value={periodEnd} min={periodStart || undefined} onChange={event => setPeriodEnd(event.target.value)} required /></label></div></>}
            {jobType === "SEND_EMAIL" && <><label>Email template<select value={template} onChange={event => setTemplate(event.target.value)}><option>ORDER_CONFIRMED</option><option>JOB_FAILED</option></select></label><label>Recipient<input type="email" value={recipient} onChange={event => setRecipient(event.target.value)} placeholder="name@example.com" required /></label><label>Variables (JSON)<textarea value={variables} onChange={event => setVariables(event.target.value)} rows={4} spellCheck="false" required /></label></>}
            {jobType === "PROCESS_FILE" && <>
              <label>Processing profile<select value={operation} onChange={event => setOperation(event.target.value)}><option value="CSV_VALIDATE">Validate CSV</option><option value="CSV_NORMALIZE">Clean, normalize &amp; deduplicate</option></select><small>{operation === "CSV_NORMALIZE" ? "Trims values, standardizes recognised dates, normalizes headers, and removes exact duplicates." : "Checks header, row width, UTF-8 text, and required values."}</small></label>
              <section className="csv-source" aria-label="CSV source">
                <div className="csv-source-heading"><div><span className="field-label">Source CSV</span><strong>Upload once, reuse for future jobs</strong></div><span className="asset-count">{sourceAssets.length} saved</span></div>
                {sourceAssets.length > 0 && <label className="asset-picker">Use an uploaded CSV<select value={selectedSourceAssetId} onChange={event => { setSelectedSourceAssetId(event.target.value); setSourceFile(null); setSourceFileError(null); setSubmitError(null); if (sourceFileInput.current) sourceFileInput.current.value = ""; }}><option value="">Upload a new CSV instead</option>{sourceAssets.map(asset => <option key={asset.id} value={asset.id}>{asset.filename} · {asset.rowCount ?? 0} rows</option>)}</select></label>}
                <input className="visually-hidden" ref={sourceFileInput} id="source-csv-file" type="file" accept=".csv,text/csv" onChange={event => selectSourceFile(event.target.files?.[0] ?? null)} />
                <label className={"csv-dropzone" + (sourceFile ? " has-file" : "")} htmlFor="source-csv-file"><span className="upload-glyph" aria-hidden="true">↑</span><span><strong>{sourceFile ? sourceFile.name : "Choose a CSV file"}</strong><small>{sourceFile ? Math.ceil(sourceFile.size / 1024) + " KB selected · it will become a reusable project asset" : "CSV only · up to 10 MB and 100,000 rows"}</small></span><span className="choose-file">Browse</span></label>
                {sourceFileError && <p className="source-validation error" role="alert">{sourceFileError}</p>}
                {selectedSourceAsset && !sourceFile && <div className="selected-asset" role="status"><span aria-hidden="true">✓</span><span><strong>Using {selectedSourceAsset.filename}</strong><small>{selectedSourceAsset.rowCount ?? 0} rows{selectedSourceAsset.header?.length ? " · " + selectedSourceAsset.header.slice(0, 3).join(", ") : ""}</small></span><button className="text-button compact" type="button" onClick={() => setSelectedSourceAssetId("")}>Change</button></div>}
              </section>
            </>}
            {jobType === "SEND_NOTIFICATION" && <><label>Notification template<select value={template} onChange={event => setTemplate(event.target.value)}><option>JOB_COMPLETED</option><option>JOB_FAILED</option></select></label><label>Recipient<input type="email" value={recipient} onChange={event => setRecipient(event.target.value)} placeholder="name@example.com" required /></label></>}
            {submitError && <p className="form-feedback error" role="alert">{submitError}</p>}
            <button className="primary submit-button" disabled={busy || Boolean(sourceFileError)}><span className="button-content">{busy && <i className="spinner" aria-hidden="true" />}{busy ? jobType === "PROCESS_FILE" && sourceFile ? "Uploading CSV…" : "Submitting…" : scheduleLater ? "Schedule job" : "Queue now"}</span></button>
          </form>
        </section>
        <aside className="side-stack">
          <section className="panel surface keys">
            <div className="panel-heading"><div><p className="eyebrow">Programmatic access</p><h2>API keys</h2></div><span>Project-scoped</span></div>
            {createdKey && <div className="secret"><div className="secret-heading"><span aria-hidden="true">◆</span><div><strong>Copy this key now</strong><p>For security, it is shown only once.</p></div></div><code>{createdKey.plaintextKey}</code><div className="secret-actions"><button className="secondary" onClick={() => void copy(createdKey.plaintextKey, "API key")}>Copy key</button><button className="text-button" onClick={() => setCreatedKey(null)}>Hide key</button></div></div>}
            <form className="new-key" onSubmit={createKey}><input value={keyName} onChange={event => setKeyName(event.target.value)} placeholder="Client name, e.g. CI" minLength={3} aria-label="API key client name" required /><button disabled={busy}>Create key</button></form>
            <div className="key-list">{keys.length === 0 ? <p className="muted">No API keys yet.</p> : keys.map(key => <div key={key.id}><span><strong>{key.name}</strong><code>{key.prefix}…</code></span><span className={statusClass(key.status)}>{key.status}</span>{key.status === "ACTIVE" && <button className="danger" onClick={() => setKeyPendingRevocation(key)} disabled={busy}>Revoke</button>}</div>)}</div>
          </section>
          <section className="tip-card"><strong>How this runs</strong><p>{jobType === "PROCESS_FILE" ? "CSV jobs run the real validation and cleanup workflow." : "This local provider simulation remains visible for about 1–2 seconds. Scheduled work waits until its chosen time."}</p></section>
        </aside>
      </div>
      <section className="panel surface jobs">
        <div className="panel-heading"><div><p className="eyebrow">Activity</p><h2>Job history</h2></div><div className="refresh-info"><span>{lastUpdated ? "Updated " + lastUpdated.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }) : "Loading…"}</span><button className="secondary" onClick={() => void refreshJobs()} disabled={busy || refreshing}><span className="button-content">{refreshing && <i className="spinner" aria-hidden="true" />}{refreshing ? "Refreshing…" : "Refresh"}</span></button></div></div>
        <div className="job-filters" aria-label="Filter job history">
          <label>State<select value={statusFilter} onChange={event => setStatusFilter(event.target.value as JobFilterStatus)}><option value="ALL">All states</option><option value="FAILED">Failed</option><option value="COMPLETED">Completed</option><option value="RUNNING">Running</option><option value="QUEUED">Queued</option><option value="PENDING">Scheduled</option><option value="RETRY_WAIT">Retrying</option><option value="CANCELLED">Cancelled</option></select></label>
          <label>Job type<select value={typeFilter} onChange={event => setTypeFilter(event.target.value)}><option value="ALL">All job types</option><option value="GENERATE_REPORT">Generate report</option><option value="SEND_EMAIL">Send email</option><option value="PROCESS_FILE">Process file</option><option value="SEND_NOTIFICATION">Send notification</option></select></label>
          <label>Priority<select value={priorityFilter} onChange={event => setPriorityFilter(event.target.value)}><option value="ALL">All priorities</option><option value="HIGH">High</option><option value="DEFAULT">Default</option><option value="LOW">Low</option></select></label>
          <div className="filter-summary"><strong>{filteredJobs.length}</strong><span>{filteredJobs.length === 1 ? "job" : "jobs"} shown</span>{hasActiveFilters && <button className="text-button" onClick={clearFilters}>Clear</button>}</div>
        </div>
        {jobs.length === 0 ? <div className="empty-state"><div aria-hidden="true">⌁</div><h3>No jobs yet</h3><p>Queue your first job above. Its full run and attempt history will appear here.</p></div> : filteredJobs.length === 0 ? <div className="empty-state filtered-empty"><div aria-hidden="true">⌕</div><h3>No matching jobs</h3><p>Try clearing a filter or choose a different project.</p>{hasActiveFilters && <button className="secondary" onClick={clearFilters}>Clear filters</button>}</div> : <div className="job-list">{filteredJobs.map(job => <article key={job.id} className="job-card"><div className="job-title"><div><strong>{jobLabel(job.jobType)}</strong><small>{display(job.createdAt)} · {job.priority} priority</small></div><span className={statusClass(job.status)}>{job.status.replace("_", " ")}</span></div><div className="job-facts"><span><b>Schedule</b>{job.scheduledAt ? display(job.scheduledAt) : "Now"}</span><span><b>Attempts</b>{job.runs.reduce((sum, run) => sum + run.executions.length, 0)}</span><span><b>Finished</b>{display(job.completedAt)}</span><span><b>Elapsed</b>{elapsedTime(job.scheduledAt ?? job.createdAt, job.completedAt) ?? "—"}</span></div><div className="job-actions"><div className="job-primary-actions"><button className="secondary" onClick={() => navigate("/jobs/" + job.id)}>View job</button>{["PENDING", "QUEUED"].includes(job.status) && <button className="danger" onClick={() => void cancel(job)} disabled={busy}>Cancel</button>}{job.status === "FAILED" && <button className="secondary" onClick={() => void retry(job)} disabled={busy}>Retry</button>}</div></div><details className="job-history"><summary>Execution history <span>{job.runs.length} {job.runs.length === 1 ? "run" : "runs"}</span></summary>{job.runs.map(run => <div className="run" key={run.id}><div className="run-summary"><strong>Run {run.runNumber}</strong>{run.status !== job.status && <span className={statusClass(run.status)}>{run.status}</span>}<span>{run.status === "COMPLETED" ? "Finished after " + run.attemptCount + " " + (run.attemptCount === 1 ? "attempt" : "attempts") : run.attemptCount + "/" + run.maxAttempts + " attempts"}</span></div>{run.executions.length === 0 ? <p className="muted">Waiting for a worker to claim this run.</p> : run.executions.map(attempt => { const isFileResult = attempt.result?.type === "PROCESS_FILE"; const hasArtifact = Boolean(artifactReference(attempt)); const hasResult = Boolean(attempt.result || hasArtifact); return <div className="attempt" key={attempt.id}><div><strong>Attempt {attempt.attemptNumber}</strong>{attempt.status !== "COMPLETED" && <span className={statusClass(attempt.status)}>{attempt.status}</span>}</div><span>{attempt.status === "COMPLETED" ? "Finished successfully" : "Finished with " + attempt.status.toLowerCase().replace("_", " ")}</span><time dateTime={attempt.finishedAt ?? attempt.startedAt}>{display(attempt.finishedAt ?? attempt.startedAt)}</time>{attempt.errorCode && <span className="error">{attempt.errorCode}</span>}{isFileResult && <div className="attempt-result-summary"><span>{numberResult(attempt.result, "validRows")} valid · {numberResult(attempt.result, "rejectedRows")} rejected · {numberResult(attempt.result, "duplicatesRemoved")} duplicates removed</span><button className="text-button" onClick={() => navigate("/jobs/" + job.id + "/result")}>Review CSV result</button></div>}{hasResult && !isFileResult && <div className="attempt-result-summary"><span>{hasArtifact ? "A downloadable result is available." : "This execution has a recorded outcome."}</span><button className="text-button" onClick={() => navigate("/jobs/" + job.id + "/result")}>View result</button></div>}</div>; })}</div>)}</details></article>)}</div>}
      </section>
    </>}
    {keyPendingRevocation && <div className="dialog-backdrop" role="presentation" onMouseDown={() => !busy && setKeyPendingRevocation(null)}><section className="confirm-dialog surface" role="alertdialog" aria-modal="true" aria-labelledby="revoke-title" aria-describedby="revoke-description" onMouseDown={event => event.stopPropagation()}><p className="eyebrow">Irreversible action</p><h2 id="revoke-title">Revoke “{keyPendingRevocation.name}”?</h2><p id="revoke-description">This key will immediately lose access to this project. Existing integrations using it will stop working.</p><div className="dialog-actions"><button className="secondary" onClick={() => setKeyPendingRevocation(null)} disabled={busy}>Keep key</button><button className="danger solid-danger" onClick={() => void revokeKey()} disabled={busy}>{busy ? "Revoking…" : "Revoke key"}</button></div></section></div>}
  </main>;
}
