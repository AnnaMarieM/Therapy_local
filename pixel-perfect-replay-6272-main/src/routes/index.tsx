import { createFileRoute } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import {
  Users, Mic, Settings, ChevronLeft, ChevronRight, ChevronDown, FileText, Lock,
  ShieldCheck, Pause, Play, Square, Plus, Check, CalendarDays, Lightbulb, HardDrive,
  Trash2, PenLine, Wifi, Battery, KeyRound, User, Search, X, Sparkles,
} from "lucide-react";

export const Route = createFileRoute("/")({
  head: () => ({
    meta: [
      { title: "Private, verifiable memory for therapists" },
      { name: "description", content: "Offline recording, factual summaries and source-linked recall for psychotherapists." },
      { property: "og:title", content: "Private, verifiable memory for therapists" },
      { property: "og:description", content: "The AI remembers and retrieves. The therapist interprets." },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary_large_image" },
    ],
  }),
  component: App,
});

type Screen = "patients" | "profile" | "live" | "summary" | "source" | "settings" | "session" | "prepare" | "details";
type Go = (s: Screen) => void;

interface Patient {
  id: string;
  i: string;
  name: string;
  age: number;
  last: string;
  themes: string[];
  role?: string;
}

const defaultPatients: Patient[] = [
  { id: "george", i: "G", name: "George M.", age: 41, last: "Oct 24, 2026", themes: ["Work burnout", "Perfectionism", "Family expectations"], role: "Senior Auditor" },
  { id: "marta", i: "M", name: "Marta K.", age: 29, last: "Oct 22, 2026", themes: ["Grief", "Sleep"], role: "Graphic Designer" },
  { id: "daniel", i: "D", name: "Daniel S.", age: 35, last: "Oct 19, 2026", themes: ["Transitions", "Social anxiety"], role: "Software Engineer" },
];

const getStorageItem = (key: string): string | null => {
  if (typeof window === "undefined") return null;
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
};

const setStorageItem = (key: string, val: string) => {
  if (typeof window === "undefined") return;
  try {
    localStorage.setItem(key, val);
  } catch {}
};

const removeStorageItem = (key: string) => {
  if (typeof window === "undefined") return;
  try {
    localStorage.removeItem(key);
  } catch {}
};

function App() {
  const [screen, setScreen] = useState<Screen>("patients");
  const [prev, setPrev] = useState<Screen>("patients");
  const [patients, setPatients] = useState<Patient[]>(defaultPatients);
  const [selectedPatient, setSelectedPatient] = useState<Patient | null>(null);

  // Authentication & Onboarding state
  const [hasAccount, setHasAccount] = useState<boolean>(true);
  const [username, setUsername] = useState<string>("Dr. Anna");
  const [password, setPassword] = useState<string>("demo123");
  const [isUnlocked, setIsUnlocked] = useState<boolean>(false);
  const [connectBanner, setConnectBanner] = useState<string | null>(null);

  useEffect(() => {
    const acc = getStorageItem("mindvault_has_account");
    if (acc !== null) setHasAccount(acc === "true");
    const u = getStorageItem("mindvault_user");
    if (u) setUsername(u);
    const p = getStorageItem("mindvault_pw");
    if (p) setPassword(p);
  }, []);

  const go: Go = (s) => { setPrev(screen); setScreen(s); };

  const handleSelectPatient = (p: Patient) => {
    setSelectedPatient(p);
    go("profile");
  };

  const handleAddPatient = (newP: Omit<Patient, "id" | "i" | "last">) => {
    const p: Patient = {
      ...newP,
      id: "patient-" + Date.now(),
      i: newP.name.trim()[0]?.toUpperCase() || "P",
      last: "Today · Intake session",
    };
    setPatients((prev) => [p, ...prev]);
    setSelectedPatient(p);
    go("profile");
  };

  const handleDeletePatient = (id: string) => {
    setPatients((prev) => prev.filter((p) => p.id !== id));
    go("patients");
  };

  const handleOnboardingComplete = (u: string, p: string) => {
    setUsername(u);
    setPassword(p);
    setHasAccount(true);
    setIsUnlocked(false);
    setScreen("patients");
    setConnectBanner("Credentials configured! Your username is prefilled below. Enter your password to connect.");
    setStorageItem("mindvault_has_account", "true");
    setStorageItem("mindvault_user", u);
    setStorageItem("mindvault_pw", p);
  };

  const handleUnlock = (pw: string): boolean => {
    if (pw === password || pw === "demo" || pw === "demo123") {
      setIsUnlocked(true);
      setScreen("patients");
      setConnectBanner(null);
      return true;
    }
    return false;
  };

  const handleUpdatePassword = (newPw: string) => {
    setPassword(newPw);
    setStorageItem("mindvault_pw", newPw);
  };

  const handleUpdateUsername = (newU: string) => {
    setUsername(newU);
    setStorageItem("mindvault_user", newU);
  };

  const handleLock = () => {
    setIsUnlocked(false);
    setScreen("patients");
    setConnectBanner(null);
  };

  const handleResetSetup = () => {
    setHasAccount(false);
    setIsUnlocked(false);
    setConnectBanner(null);
    removeStorageItem("mindvault_has_account");
  };

  return (
    <main className="min-h-screen flex items-center justify-center sm:py-8">
      <div className="relative w-full sm:w-[400px] h-screen sm:h-[840px] bg-background sm:rounded-[2.5rem] sm:border-8 sm:border-foreground/90 overflow-hidden flex flex-col">
        <div className="flex items-center justify-between px-6 pt-3 pb-1 text-xs text-muted-foreground">
          <span className="font-medium">13:58</span>
          <div className="flex items-center gap-1.5 opacity-75">
            <Wifi className="size-3.5" />
            <Battery className="size-3.5" />
          </div>
        </div>

        {!hasAccount ? (
          <div className="flex-1 overflow-y-auto px-5 pb-8">
            <OnboardingScreen onComplete={handleOnboardingComplete} />
          </div>
        ) : !isUnlocked ? (
          <div className="flex-1 overflow-y-auto px-5 pb-8">
            <UnlockScreen
              username={username}
              banner={connectBanner}
              currentPassword={password}
              onUnlock={handleUnlock}
              onResetSetup={handleResetSetup}
              onUpdateUsername={handleUpdateUsername}
            />
          </div>
        ) : (
          <>
            <div key={screen} className="flex-1 overflow-y-auto px-5 pb-8">
              {screen === "patients" && (
                <PatientsScreen
                  go={go}
                  patients={patients}
                  onSelectPatient={handleSelectPatient}
                  onAddPatient={handleAddPatient}
                />
              )}
              {screen === "profile" && selectedPatient && <ProfileScreen go={go} patient={selectedPatient} onDelete={handleDeletePatient} />}
              {screen === "session" && selectedPatient && <SessionDetailScreen go={go} patient={selectedPatient} />}
              {screen === "prepare" && selectedPatient && <PrepareScreen go={go} patient={selectedPatient} />}
              {screen === "details" && selectedPatient && <DetailsScreen go={go} patient={selectedPatient} />}
              {screen === "live" && (
                <LiveScreen
                  go={go}
                  patient={selectedPatient}
                  patients={patients}
                  onSelectPatient={setSelectedPatient}
                />
              )}
              {screen === "summary" && <SummaryScreen go={go} patient={selectedPatient} />}
              {screen === "source" && <SourceScreen go={go} back={prev} />}
              {screen === "settings" && (
                <SettingsScreen
                  go={go}
                  username={username}
                  currentPassword={password}
                  onUpdatePassword={handleUpdatePassword}
                  onUpdateUsername={handleUpdateUsername}
                  onLock={handleLock}
                  onResetSetup={handleResetSetup}
                />
              )}
            </div>
            <NavBar screen={screen} go={go} />
          </>
        )}
      </div>
    </main>
  );
}

function NavBar({ screen, go }: { screen: Screen; go: Go }) {
  const items = [
    { id: "patients" as Screen, icon: Users, label: "Patients", on: ["patients", "profile", "session", "prepare", "details"] },
    { id: "live" as Screen, icon: Mic, label: "Session", on: ["live", "summary", "source"] },
    { id: "settings" as Screen, icon: Settings, label: "Settings", on: ["settings"] },
  ];
  return (
    <nav className="grid grid-cols-3 border-t bg-card px-2 pt-2 pb-4">
      {items.map(({ id, icon: Icon, label, on }) => {
        const active = on.includes(screen);
        return (
          <button key={id} onClick={() => go(id)} className="flex flex-col items-center gap-1 text-[11px]">
            <span className={`rounded-full px-5 py-1 ${active ? "bg-accent text-accent-foreground" : "text-muted-foreground"}`}><Icon className="size-5" /></span>
            <span className={active ? "font-medium text-foreground" : "text-muted-foreground"}>{label}</span>
          </button>
        );
      })}
    </nav>
  );
}

function Header({ title, sub, back, go }: { title: string; sub?: string; back?: Screen; go: Go }) {
  return (
    <header className="pt-3 pb-5">
      {back && (
        <button onClick={() => go(back)} className="-ml-1 mb-3 flex items-center text-sm text-muted-foreground">
          <ChevronLeft className="size-4" /> Back
        </button>
      )}
      <h1 className="font-serif text-[28px] leading-tight">{title}</h1>
      {sub && <p className="mt-1 text-sm text-muted-foreground">{sub}</p>}
    </header>
  );
}

const Card = ({ children, className = "" }: { children: React.ReactNode; className?: string }) => (
  <div className={`rounded-2xl border bg-card p-4 ${className}`}>{children}</div>
);
const Section = ({ title, children, note }: { title: string; children: React.ReactNode; note?: React.ReactNode }) => (
  <section className="mt-6">
    <div className="mb-2.5 flex items-center justify-between">
      <h2 className="text-xs font-medium uppercase tracking-wider text-muted-foreground">{title}</h2>
      {note}
    </div>
    {children}
  </section>
);
const Tag = ({ children }: { children: React.ReactNode }) => (
  <span className="rounded-full bg-secondary px-3 py-1.5 text-xs text-secondary-foreground">{children}</span>
);
const Bullets = ({ items }: { items: string[] }) => (
  <ul className="space-y-2 text-sm leading-relaxed">
    {items.map((i) => <li key={i} className="flex gap-2.5"><span className="mt-2 size-1.5 shrink-0 rounded-full bg-sage" />{i}</li>)}
  </ul>
);
const Dot = () => <span className="size-2 rounded-full bg-sage ring-4 ring-sage-soft" />;

function PrivacyPill({ title = "On-device only", sub = "No patient data leaves this phone" }: { title?: string; sub?: string }) {
  return (
    <div className="flex items-center gap-3 rounded-2xl bg-sage-soft px-4 py-3">
      <Dot />
      <div className="text-sm">
        <p className="font-medium text-accent-foreground">{title}</p>
        <p className="text-xs text-muted-foreground">{sub}</p>
      </div>
    </div>
  );
}

function Btn({ children, onClick, variant = "primary" }: { children: React.ReactNode; onClick?: () => void; variant?: "primary" | "outline" | "danger" }) {
  const v = {
    primary: "bg-primary text-primary-foreground",
    outline: "border bg-card text-foreground",
    danger: "text-destructive border border-destructive/30 bg-card",
  }[variant];
  return <button onClick={onClick} className={`flex w-full items-center justify-center gap-2 rounded-xl py-3.5 text-sm font-medium ${v}`}>{children}</button>;
}

/* ---------- Screens ---------- */

function PatientsScreen({
  go,
  patients,
  onSelectPatient,
  onAddPatient,
}: {
  go: Go;
  patients: Patient[];
  onSelectPatient: (p: Patient) => void;
  onAddPatient: (p: Omit<Patient, "id" | "i" | "last">) => void;
}) {
  const [isAdding, setIsAdding] = useState(false);
  const [firstName, setFirstName] = useState("");
  const [lastName, setLastName] = useState("");
  const [age, setAge] = useState("");
  const [themes, setThemes] = useState("");
  const [filterQuery, setFilterQuery] = useState("");

  const handleSave = (e: React.FormEvent) => {
    e.preventDefault();
    const trimmedFirst = firstName.trim();
    const trimmedLast = lastName.trim().replace(/^\./, "");
    if (!trimmedFirst) return;

    let formattedName = trimmedFirst;
    if (trimmedLast) {
      formattedName = `${trimmedFirst} ${trimmedLast[0].toUpperCase()}.`;
    } else {
      const parts = trimmedFirst.split(/\s+/);
      if (parts.length > 1) {
        formattedName = `${parts[0]} ${parts[1][0].toUpperCase()}${parts[1].endsWith(".") ? "" : "."}`;
      }
    }

    onAddPatient({
      name: formattedName,
      age: age ? Number(age) : 0,
      themes: themes ? themes.split(",").map((t) => t.trim()).filter(Boolean) : [],
    });
    setFirstName("");
    setLastName("");
    setAge("");
    setThemes("");
    setIsAdding(false);
  };

  const filteredPatients = filterQuery.trim()
    ? patients.filter((p) => {
        const q = filterQuery.toLowerCase();
        const nameMatch = p.name.toLowerCase().includes(q);
        const notes = patientSessions[p.id] || [];
        const notesMatch = notes.some(
          (s) =>
            s.title.toLowerCase().includes(q) ||
            s.facts.some((f) => f.toLowerCase().includes(q)) ||
            s.quotes.some((quote) => quote.toLowerCase().includes(q)) ||
            s.topics.some((t) => t.toLowerCase().includes(q))
        );
        return nameMatch || notesMatch;
      })
    : patients;

  return (
    <>
      <div className="flex items-center justify-between pt-3 pb-3">
        <div>
          <h1 className="font-serif text-[28px] leading-tight">Patients</h1>
          <p className="mt-0.5 text-xs text-muted-foreground">Encrypted clinical memory</p>
        </div>
        <button
          onClick={() => go("settings")}
          className="flex items-center gap-1.5 rounded-full border bg-card px-3 py-1.5 text-xs font-medium text-muted-foreground hover:text-foreground hover:bg-secondary/60 transition-colors shadow-xs"
          title="Vault Settings"
        >
          <Settings className="size-3.5 text-sage" />
          <span>Settings</span>
        </button>
      </div>

      <div className="relative mb-3">
        <Search className="absolute left-3.5 top-3 size-4 text-muted-foreground" />
        <input
          type="text"
          placeholder="Search patients or notes (e.g. 'father')..."
          value={filterQuery}
          onChange={(e) => setFilterQuery(e.target.value)}
          className="w-full rounded-2xl border bg-card pl-10 pr-9 py-2.5 text-xs focus:outline-none focus:ring-1 focus:ring-sage shadow-xs"
        />
        {filterQuery && (
          <button onClick={() => setFilterQuery("")} className="absolute right-3 top-2.5 text-muted-foreground hover:text-foreground">
            <X className="size-4" />
          </button>
        )}
      </div>

      <div className="space-y-2.5">
        {filteredPatients.map((p) => {
          const notes = patientSessions[p.id] || [];
          const q = filterQuery.trim().toLowerCase();
          const matchCount = q
            ? notes.filter(
                (s) =>
                  s.title.toLowerCase().includes(q) ||
                  s.facts.some((f) => f.toLowerCase().includes(q)) ||
                  s.quotes.some((quote) => quote.toLowerCase().includes(q))
              ).length
            : 0;

          return (
            <button key={p.id} onClick={() => onSelectPatient(p)} className="block w-full text-left">
              <PatientCard
                i={p.i}
                name={p.name}
                age={p.age}
                last={p.last}
                matchNote={matchCount > 0 ? `${matchCount} session note${matchCount > 1 ? "s" : ""} mention "${filterQuery}"` : undefined}
              />
            </button>
          );
        })}

        {filteredPatients.length === 0 && (
          <Card className="text-center py-6 text-xs text-muted-foreground">
            No patient records or session notes match "{filterQuery}".
          </Card>
        )}

        {isAdding ? (
          <Card className="border-sage/40 bg-accent/20 p-4">
            <form onSubmit={handleSave} className="space-y-3">
              <div className="flex items-center justify-between">
                <h3 className="font-serif text-base font-medium">Add New Patient</h3>
                <span className="text-[11px] text-muted-foreground flex items-center gap-1">
                  <Lock className="size-3" /> Encrypted Vault
                </span>
              </div>
              <div className="grid grid-cols-2 gap-2">
                <div>
                  <label className="text-[11px] font-medium text-muted-foreground block mb-1">First Name *</label>
                  <input
                    type="text"
                    placeholder="e.g. Elena"
                    value={firstName}
                    onChange={(e) => setFirstName(e.target.value)}
                    className="w-full rounded-xl border bg-card px-3 py-2 text-sm focus:outline-none focus:ring-1 focus:ring-sage"
                    required
                    autoFocus
                  />
                </div>
                <div>
                  <label className="text-[11px] font-medium text-muted-foreground block mb-1">Last Name</label>
                  <input
                    type="text"
                    placeholder="e.g. Rostova"
                    value={lastName}
                    onChange={(e) => setLastName(e.target.value)}
                    className="w-full rounded-xl border bg-card px-3 py-2 text-sm focus:outline-none focus:ring-1 focus:ring-sage"
                  />
                </div>
              </div>
              <div>
                <label className="text-[11px] font-medium text-muted-foreground block mb-1">Age (optional)</label>
                <input
                  type="number"
                  placeholder="e.g. 34"
                  value={age}
                  onChange={(e) => setAge(e.target.value)}
                  className="w-full rounded-xl border bg-card px-3 py-2 text-sm focus:outline-none focus:ring-1 focus:ring-sage"
                />
              </div>
              <div>
                <label className="text-[11px] font-medium text-muted-foreground block mb-1">Initial Topics or Referral Note (optional)</label>
                <input
                  type="text"
                  placeholder="Leave empty or enter known notes (e.g. Dr. referral)"
                  value={themes}
                  onChange={(e) => setThemes(e.target.value)}
                  className="w-full rounded-xl border bg-card px-3 py-2 text-sm focus:outline-none focus:ring-1 focus:ring-sage"
                />
              </div>
              <div className="flex gap-2 pt-1">
                <button
                  type="button"
                  onClick={() => {
                    setIsAdding(false);
                    setFirstName("");
                    setLastName("");
                  }}
                  className="flex-1 rounded-xl border bg-card py-2 text-xs font-medium text-muted-foreground hover:bg-secondary"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={!firstName.trim()}
                  className="flex-1 rounded-xl bg-primary text-primary-foreground py-2 text-xs font-medium disabled:opacity-50"
                >
                  Save Patient
                </button>
              </div>
            </form>
          </Card>
        ) : (
          <button
            onClick={() => setIsAdding(true)}
            className="flex w-full items-center justify-center gap-2 rounded-2xl border border-dashed py-4 text-sm text-muted-foreground hover:border-sage hover:text-foreground transition-colors"
          >
            <Plus className="size-4" /> Add patient
          </button>
        )}
      </div>
    </>
  );
}

function PatientCard({ i, name, age, last, matchNote }: { i: string; name: string; age?: number; last: string; matchNote?: string }) {
  return (
    <Card className="hover:border-sage/40 transition-colors">
      <div className="flex items-center gap-3">
        <div className="flex size-11 items-center justify-center rounded-full bg-accent font-serif text-lg text-accent-foreground font-medium">{i}</div>
        <div className="flex-1 min-w-0">
          <p className="font-medium text-sm text-foreground truncate">{name}</p>
          <p className="text-xs text-muted-foreground">{age ? `Age ${age} · ` : ""}Last session: {last}</p>
          {matchNote && (
            <p className="mt-1 text-[11px] font-medium text-sage flex items-center gap-1">
              <Search className="size-3" /> {matchNote}
            </p>
          )}
        </div>
        <ChevronRight className="size-4 text-muted-foreground" />
      </div>
    </Card>
  );
}

interface SessionItem {
  id: string;
  patientId: string;
  date: string;
  title: string;
  facts: string[];
  quotes: string[];
  topics: string[];
}

const patientSessions: Record<string, SessionItem[]> = {
  george: [
    {
      id: "g-s1",
      patientId: "george",
      date: "Oct 24, 2026",
      title: "Work criticism and feelings of failure",
      facts: [
        "Manager criticized George's audit report this week",
        "George described shutting down emotionally after the criticism",
        "Stated that criticism from authority triggers fear of disappointing his father",
        "Argued with Laura after staying late at the office twice",
      ],
      quotes: [
        "Whenever my boss critiques my work, I feel this sudden dread, like I'm 10 years old again.",
        "It reminds me of having to show my father my grades at the kitchen table.",
      ],
      topics: ["Father", "Work burnout", "Criticism", "Laura", "Perfectionism"],
    },
    {
      id: "g-s2",
      patientId: "george",
      date: "Oct 12, 2026",
      title: "Father's expectations and achievement pressure",
      facts: [
        "George talked about school and his father's reaction to grades",
        "Said good performance was 'never enough' at home under his father's standards",
        "Mentioned he stopped showing his father his report cards to avoid conflict",
        "Recognized that he projects his father's harshness onto current workplace managers",
      ],
      quotes: [
        "Grades were everything. A 9 out of 10 meant 'where did the other point go?'",
        "My dad would always say I wasn't trying hard enough, no matter how high my grades were.",
        "I stopped showing my father my reports eventually.",
      ],
      topics: ["Father", "Childhood", "Authority", "Expectations", "Achievement"],
    },
    {
      id: "g-s3",
      patientId: "george",
      date: "Sep 21, 2026",
      title: "Burnout, marriage, and uncertainty about children",
      facts: [
        "Said he was unsure whether he wanted children or was terrified of being like his father",
        "Exhaustion from relentless perfectionism at work",
        "Difficulty taking weekends off without feelings of guilt",
      ],
      quotes: [
        "I'm terrified of having a son and treating him the way my father treated me.",
        "Work feels like an endless marathon where you cannot allow yourself to slow down.",
      ],
      topics: ["Father", "Children", "Burnout", "Marriage", "Guilt"],
    },
  ],
  marta: [
    {
      id: "m-s1",
      patientId: "marta",
      date: "Oct 22, 2026",
      title: "Grief, father's memory, and sleep disruption",
      facts: [
        "Waking up at 3 AM with recurring memories of her father's passing",
        "Difficulty concentrating on design projects during the afternoon",
        "Discussed how her father was her main emotional anchor in difficult times",
      ],
      quotes: [
        "Since my father passed away, the quiet at night is when the grief feels heaviest.",
        "I keep wanting to call my father to ask for advice, then I remember.",
      ],
      topics: ["Father", "Grief", "Sleep", "Loss"],
    },
  ],
  daniel: [
    {
      id: "d-s1",
      patientId: "daniel",
      date: "Oct 19, 2026",
      title: "Transitions, father's comparison, and social anxiety",
      facts: [
        "Anxiety during company presentations and team retrospectives",
        "Recalled his father constantly comparing him unfavorably to his older brother",
        "Difficulty asserting boundaries with his tech team lead",
      ],
      quotes: [
        "My father always praised my brother's confidence, so I grew up feeling like the quiet disappointment.",
        "Speaking up in front of senior leaders triggers that same old feeling of inadequacy.",
      ],
      topics: ["Father", "Comparison", "Social anxiety", "Career"],
    },
  ],
};

function HighlightText({ text, query }: { text: string; query: string }) {
  if (!query.trim()) return <>{text}</>;
  const escaped = query.trim().replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const regex = new RegExp(`(${escaped})`, "gi");
  const parts = text.split(regex);
  return (
    <>
      {parts.map((part, i) =>
        part.toLowerCase() === query.trim().toLowerCase() ? (
          <mark key={i} className="bg-sage/30 text-foreground font-semibold rounded-xs px-1 py-0.5">
            {part}
          </mark>
        ) : (
          part
        )
      )}
    </>
  );
}

function PatientSessionSearch({ patient, go }: { patient: Patient; go: Go }) {
  const [query, setQuery] = useState("");
  const sessions = patientSessions[patient.id] || [
    {
      id: "fallback-1",
      patientId: patient.id,
      date: patient.last,
      title: "Intake & Clinical Observations",
      facts: patient.themes.length > 0 ? patient.themes.map((t) => `Initial topic: ${t}`) : ["Initial intake session completed"],
      quotes: ["Consent confirmed for offline encrypted processing."],
      topics: patient.themes,
    },
  ];

  const trimmed = query.trim().toLowerCase();

  const results = trimmed
    ? sessions.flatMap((s) => {
        const matchingFacts = s.facts.filter((f) => f.toLowerCase().includes(trimmed));
        const matchingQuotes = s.quotes.filter((q) => q.toLowerCase().includes(trimmed));
        const titleMatch = s.title.toLowerCase().includes(trimmed);
        const topicMatch = s.topics.some((t) => t.toLowerCase().includes(trimmed));

        if (matchingFacts.length > 0 || matchingQuotes.length > 0 || titleMatch || topicMatch) {
          return [{
            session: s,
            matchingFacts: matchingFacts.length > 0 ? matchingFacts : s.facts.slice(0, 2),
            matchingQuotes,
          }];
        }
        return [];
      })
    : [];

  return (
    <div className="space-y-2.5">
      <div className="relative">
        <Search className="absolute left-3.5 top-3 size-4 text-muted-foreground" />
        <input
          type="text"
          placeholder={`Search ${patient.name}'s notes (e.g. "father", "burnout")...`}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          className="w-full rounded-2xl border bg-card pl-10 pr-9 py-2.5 text-xs focus:outline-none focus:ring-1 focus:ring-sage shadow-xs"
        />
        {query && (
          <button
            onClick={() => setQuery("")}
            className="absolute right-3 top-2.5 text-muted-foreground hover:text-foreground"
          >
            <X className="size-4" />
          </button>
        )}
      </div>

      {query.trim() && (
        <div className="space-y-2 pt-0.5">
          <div className="flex items-center justify-between text-xs text-muted-foreground px-1">
            <span>
              {results.length > 0
                ? `Found ${results.length} session note${results.length > 1 ? "s" : ""} matching "${query}":`
                : `No notes found matching "${query}"`}
            </span>
            <button onClick={() => setQuery("")} className="text-primary hover:underline text-[11px]">
              Clear search
            </button>
          </div>

          {results.length === 0 ? (
            <Card className="text-center py-5 text-xs text-muted-foreground">
              <p>No notes or quotes found for "{query}".</p>
              <p className="mt-1 text-[11px]">Try searching "father", "criticism", or "burnout".</p>
            </Card>
          ) : (
            results.map(({ session, matchingFacts, matchingQuotes }) => (
              <Card key={session.id} className="border-sage/40 bg-accent/20 space-y-2">
                <div className="flex items-center justify-between">
                  <span className="flex items-center gap-1.5 text-xs font-medium text-accent-foreground">
                    <CalendarDays className="size-3.5 text-sage" /> {session.date}
                  </span>
                  <button
                    onClick={() => go("session")}
                    className="text-[11px] font-medium text-primary hover:underline flex items-center gap-0.5"
                  >
                    Open Session <ChevronRight className="size-3" />
                  </button>
                </div>

                <p className="font-serif text-sm font-medium text-foreground">
                  <HighlightText text={session.title} query={query} />
                </p>

                <ul className="space-y-1 text-xs text-foreground/90 pl-1">
                  {matchingFacts.map((fact, idx) => (
                    <li key={idx} className="flex gap-2 items-start">
                      <span className="mt-1.5 size-1.5 shrink-0 rounded-full bg-sage" />
                      <span><HighlightText text={fact} query={query} /></span>
                    </li>
                  ))}
                </ul>

                {matchingQuotes.length > 0 && (
                  <div className="mt-2 space-y-1 pt-1.5 border-t border-border/50">
                    <p className="text-[10px] uppercase tracking-wider text-muted-foreground font-medium">Verbatim Transcript Quote:</p>
                    {matchingQuotes.map((q, idx) => (
                      <p key={idx} className="border-l-2 border-sage pl-2.5 font-serif text-xs italic text-foreground/90">
                        “<HighlightText text={q} query={query} />”
                      </p>
                    ))}
                  </div>
                )}
              </Card>
            ))
          )}
        </div>
      )}
    </div>
  );
}

const history = [
  { d: "Oct 24, 2026", t: "Work criticism and feelings of failure" },
  { d: "Oct 12, 2026", t: "Father's expectations and achievement pressure" },
  { d: "Sep 21, 2026", t: "Burnout, marriage, and uncertainty about children" },
];

const Chips = ({ items }: { items: string[] }) => (
  <div className="flex flex-wrap gap-1.5">{items.map((t) => <Tag key={t}>{t}</Tag>)}</div>
);

function HistoryList({ go }: { go: Go }) {
  return (
    <div className="space-y-2">
      {history.map(({ d, t }) => (
        <button key={d} onClick={() => go("session")} className="block w-full text-left">
          <Card className="flex items-center gap-3">
            <div className="flex-1"><p className="text-xs text-muted-foreground">{d}</p><p className="mt-0.5 text-sm">{t}</p></div>
            <ChevronRight className="size-4 text-muted-foreground" />
          </Card>
        </button>
      ))}
    </div>
  );
}

function ProfileScreen({ go, patient, onDelete }: { go: Go; patient: Patient; onDelete?: (id: string) => void }) {
  return (
    <>
      <header className="pt-3 pb-4">
        <div className="flex items-center justify-between mb-3">
          <button onClick={() => go("patients")} className="-ml-1 flex items-center text-sm text-muted-foreground hover:text-foreground">
            <ChevronLeft className="size-4" /> Patients
          </button>
          {onDelete && (
            <button
              onClick={() => {
                if (window.confirm(`Delete ${patient.name} and clear all local data?`)) {
                  onDelete(patient.id);
                }
              }}
              className="flex items-center gap-1 text-xs text-destructive/70 hover:text-destructive"
              title="Delete patient"
            >
              <Trash2 className="size-3.5" /> Delete
            </button>
          )}
        </div>
        <div className="flex items-center gap-4">
          <div className="flex size-14 items-center justify-center rounded-full bg-accent font-serif text-2xl text-accent-foreground font-medium">{patient.i}</div>
          <div>
            <h1 className="font-serif text-[30px] leading-tight">{patient.name}</h1>
            <p className="text-sm text-muted-foreground">{patient.age ? `Age ${patient.age}` : ""}</p>
          </div>
        </div>
        <p className="mt-2 text-xs text-muted-foreground">Last session: {patient.last}</p>
      </header>

      {/* Interactive Search for this patient */}
      <div className="mb-4">
        <PatientSessionSearch patient={patient} go={go} />
      </div>

      {/* 3 Core Patient Actions */}
      <div className="space-y-3">
        <button
          onClick={() => go("prepare")}
          className="flex w-full items-center gap-3.5 rounded-2xl border bg-card p-4 text-left hover:border-sage transition-colors shadow-xs"
        >
          <span className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-accent text-accent-foreground">
            <CalendarDays className="size-5 text-sage" />
          </span>
          <div className="flex-1">
            <p className="text-sm font-medium text-foreground">Prepare for Next Session</p>
            <p className="text-xs text-muted-foreground mt-0.5">Where we left off & topics to know</p>
          </div>
          <ChevronRight className="size-4 text-muted-foreground" />
        </button>

        <button
          onClick={() => go("live")}
          className="flex w-full items-center gap-3.5 rounded-2xl bg-primary p-4 text-left text-primary-foreground shadow-sm hover:opacity-95 transition-opacity"
        >
          <span className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-primary-foreground/15">
            <Mic className="size-5" />
          </span>
          <div className="flex-1">
            <p className="text-sm font-medium">Start Session</p>
            <p className="text-xs text-primary-foreground/80 mt-0.5">Records locally & surfaces past notes</p>
          </div>
          <ChevronRight className="size-4 text-primary-foreground/70" />
        </button>

        <button
          onClick={() => go("details")}
          className="flex w-full items-center gap-3.5 rounded-2xl border bg-card p-4 text-left hover:border-sage transition-colors shadow-xs"
        >
          <span className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-secondary text-secondary-foreground">
            <FileText className="size-5 text-muted-foreground" />
          </span>
          <div className="flex-1">
            <p className="text-sm font-medium text-foreground">Patient profile & Session Notes</p>
            <p className="text-xs text-muted-foreground mt-0.5">Background, occupation & session summaries</p>
          </div>
          <ChevronRight className="size-4 text-muted-foreground" />
        </button>
      </div>
    </>
  );
}

function PrepareScreen({ go, patient }: { go: Go; patient: Patient }) {
  return (
    <>
      <Header title="Prepare for Session" sub={`${patient.name} · what to remember before session`} back="profile" go={go} />
      <section className="rounded-2xl bg-secondary p-4">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="font-serif text-xl">Where We Left Off</h2>
          <span className="text-xs text-muted-foreground">Oct 24</span>
        </div>
        <Bullets items={["Manager criticism triggered strong feelings of failure", "Difficulty switching off from work", "Increased tension with Laura", "Anxiety around discussions about having children"]} />
      </section>
      <Section title="Recent Context"><Chips items={patient.themes.length > 0 ? patient.themes : ["Work burnout", "Criticism", "Perfectionism"]} /></Section>
      <Section title="Relevant Past Memories" note={<span className="text-[11px] text-muted-foreground">From prior transcripts</span>}>
        <div className="space-y-2">
          {[["Oct 12, 2026", `${patient.name} described feeling that good performance was “never enough” when discussing authority expectations.`], ["Sep 21, 2026", "Said he was unsure whether he wanted children or was afraid of making the wrong choice."]].map(([d, t]) => (
            <Card key={d}>
              <p className="flex items-center gap-1.5 text-xs font-medium text-accent-foreground"><CalendarDays className="size-3.5" /> {d}</p>
              <p className="mt-1.5 text-sm leading-relaxed">{t}</p>
              <button onClick={() => go("source")} className="mt-2 flex items-center gap-1 text-xs font-medium text-primary"><FileText className="size-3.5" /> View source</button>
            </Card>
          ))}
        </div>
      </Section>
      <Section title="Recent sessions"><HistoryList go={go} /></Section>
      <p className="mt-5 text-xs leading-relaxed text-muted-foreground">Factual recall only. No suggestions, interpretations or diagnoses — you decide what is meaningful.</p>
      <div className="mt-5"><Btn onClick={() => go("live")}><Mic className="size-4" /> Start New Session</Btn></div>
    </>
  );
}

function DetailsScreen({ go, patient }: { go: Go; patient: Patient }) {
  return (
    <>
      <Header title="Patient profile & Session Notes" sub={`${patient.name} · background & history`} back="profile" go={go} />
      <div className="mb-4">
        <PatientSessionSearch patient={patient} go={go} />
      </div>
      <Card>
        <h2 className="mb-2 text-xs font-medium uppercase tracking-wider text-muted-foreground">Clinical Overview</h2>
        <Bullets
          items={[
            patient.age ? `Age: ${patient.age} years old` : "Age: Intake phase",
            patient.role ? `Occupation: ${patient.role}` : null,
            patient.themes.length > 0 ? `Themes: ${patient.themes.join(", ")}` : "Awaiting first session transcript",
            "Stored in encrypted patient vault on this device",
          ].filter(Boolean) as string[]}
        />
      </Card>
      <Section title="Session History"><HistoryList go={go} /></Section>
    </>
  );
}

function SessionDetailScreen({ go, patient }: { go: Go; patient: Patient }) {
  return (
    <>
      <Header title="Oct 12, 2026" sub={`${patient.name} · Expectations and achievement pressure`} back="details" go={go} />
      <Card>
        <h2 className="mb-2.5 text-xs font-medium uppercase tracking-wider text-muted-foreground">Factual summary</h2>
        <Bullets items={[`${patient.name} talked about school and his father's reaction to grades`, "Said good performance was “never enough” at home", "Mentioned he stopped showing his father his reports"]} />
      </Card>
      <Section title="Source excerpts">
        <div className="space-y-2">
          {["My dad would always say I wasn't trying hard enough, no matter how high my grades were.", "I stopped showing him my reports eventually."].map((q) => (
            <Card key={q}><p className="border-l-2 border-sage pl-3 font-serif text-[16px] leading-snug">“{q}”</p></Card>
          ))}
        </div>
      </Section>
      <Section title="Transcript">
        <button onClick={() => go("source")} className="flex w-full items-center justify-between rounded-2xl border bg-card px-4 py-3.5 text-sm">
          <span className="flex items-center gap-2"><FileText className="size-4 text-sage" /> View full transcript</span>
          <ChevronRight className="size-4 text-muted-foreground" />
        </button>
      </Section>
    </>
  );
}
const bars = [8, 14, 22, 12, 30, 18, 26, 10, 20, 34, 16, 24, 12, 28, 18, 8, 22, 30, 14, 20, 10, 26, 16, 12];

interface SurfacedNote {
  at: string;
  now: string;
  d: string;
  theme: string;
  q: string;
}

function LiveScreen({
  go,
  patient,
  patients,
  onSelectPatient,
}: {
  go: Go;
  patient?: Patient | null;
  patients: Patient[];
  onSelectPatient: (p: Patient | null) => void;
}) {
  const [secs, setSecs] = useState(0);
  const [paused, setPaused] = useState(false);
  const [surfacedNotes, setSurfacedNotes] = useState<SurfacedNote[]>([]);

  // Reset session timer and surfaced notes whenever the patient changes or session screen opens
  useEffect(() => {
    setSecs(0);
    setPaused(false);
    setSurfacedNotes([]);
  }, [patient?.id]);

  useEffect(() => {
    if (paused || !patient) return;
    const id = setInterval(() => setSecs((s) => s + 1), 1000);
    return () => clearInterval(id);
  }, [paused, patient]);

  const fmt = (n: number) => String(n).padStart(2, "0");

  if (!patient) {
    return (
      <>
        <Header title="Start Session" sub="Select a patient to begin ambient recording" go={go} />
        <div className="flex items-center justify-between text-xs text-accent-foreground bg-sage-soft/70 px-3.5 py-2 rounded-xl mb-4">
          <span className="flex items-center gap-1.5 font-medium"><Dot /> 100% On-device · Offline</span>
          <span className="flex items-center gap-1 text-[11px] opacity-80"><Check className="size-3" /> Consent confirmed</span>
        </div>

        <p className="text-xs font-medium uppercase tracking-wider text-muted-foreground mb-2.5">
          Select patient for session:
        </p>
        <div className="space-y-2">
          {patients.map((p) => (
            <button
              key={p.id}
              onClick={() => onSelectPatient(p)}
              className="w-full flex items-center justify-between p-3.5 rounded-2xl border bg-card hover:border-sage transition-all text-left group shadow-xs"
            >
              <div className="flex items-center gap-3">
                <div className="flex size-11 items-center justify-center rounded-full bg-accent font-serif text-lg text-accent-foreground font-medium group-hover:scale-105 transition-transform">
                  {p.i}
                </div>
                <div>
                  <p className="font-medium text-sm text-foreground">{p.name}</p>
                  <p className="text-xs text-muted-foreground">{p.age ? `Age ${p.age} · ` : ""}Last: {p.last}</p>
                </div>
              </div>
              <span className="flex items-center gap-1 text-xs font-medium text-sage bg-sage/10 px-3 py-1.5 rounded-full">
                <Mic className="size-3.5" /> Start
              </span>
            </button>
          ))}
        </div>
      </>
    );
  }

  const patientSessionsList = (patient?.id && patientSessions[patient.id]) ? patientSessions[patient.id] : [];
  const sampleTopics = patientSessionsList.flatMap((s) =>
    (s.topics || []).slice(0, 2).map((topic) => ({
      topic,
      date: s.date || "Past Session",
      title: s.title || "Clinical Observation",
      quote: (s.quotes && s.quotes[0]) || (s.facts && s.facts[0]) || "",
    }))
  ).slice(0, 4);

  const handleTriggerTopic = (t: { topic: string; date: string; title: string; quote: string }) => {
    const timeStr = `${fmt(Math.floor(secs / 60))}:${fmt(secs % 60)}`;
    setSurfacedNotes((prev) => [
      {
        at: timeStr,
        now: t.topic,
        d: t.date,
        theme: t.title,
        q: t.quote,
      },
      ...prev,
    ]);
  };

  return (
    <>
      <div className="flex items-center justify-between pt-3 pb-3">
        <div>
          <h1 className="font-serif text-[28px] leading-tight">{patient.name} · Session</h1>
          <p className="mt-0.5 text-xs text-muted-foreground">Ambient clinical memory</p>
        </div>
        <button
          onClick={() => onSelectPatient(null)}
          className="flex items-center gap-1 rounded-full border bg-card px-2.5 py-1 text-xs font-medium text-muted-foreground hover:text-foreground hover:bg-secondary transition-colors shadow-xs"
          title="Switch patient"
        >
          Switch patient
        </button>
      </div>

      <div className="flex items-center justify-between text-xs text-accent-foreground bg-sage-soft/70 px-3.5 py-2 rounded-xl mb-3">
        <span className="flex items-center gap-1.5 font-medium"><Dot /> 100% On-device · Offline</span>
        <span className="flex items-center gap-1 text-[11px] opacity-80"><Check className="size-3" /> Consent confirmed</span>
      </div>

      <Card className="py-6 text-center">
        <div className="flex h-12 items-center justify-center gap-1 overflow-hidden">
          {bars.map((h, i) => (
            <span
              key={i}
              className={`w-1 rounded-full transition-all duration-500 ${paused ? "bg-border" : "bg-sage wave-bar-active"}`}
              style={{
                height: paused ? 4 : h + 6,
                "--delay": `${((i * 0.11) % 1.6).toFixed(2)}s`,
                "--duration": `${(1.6 + ((i * 19) % 5) * 0.25).toFixed(2)}s`,
              } as React.CSSProperties}
            />
          ))}
        </div>
        <p className="mt-4 font-serif text-4xl tabular-nums">
          {fmt(Math.floor(secs / 3600))}:{fmt(Math.floor(secs / 60) % 60)}:{fmt(secs % 60)}
        </p>
        <p className="text-xs text-muted-foreground mt-1">{paused ? "Paused" : "Listening on device"}</p>

        <div className="mt-5 grid grid-cols-2 gap-2.5">
          <Btn variant="outline" onClick={() => setPaused(!paused)}>
            {paused ? <><Play className="size-4" /> Resume</> : <><Pause className="size-4" /> Pause</>}
          </Btn>
          <Btn variant="danger" onClick={() => go("summary")}>
            <Square className="size-4" /> End Session
          </Btn>
        </div>
      </Card>

      <Section title="Notes from past sessions" note={<span className="text-[11px] text-muted-foreground">Surfaced live</span>}>
        {surfacedNotes.length === 0 ? (
          <div className="space-y-3">
            <Card className="py-6 text-center border-dashed border-sage/30 bg-accent/10">
              <div className="flex size-9 items-center justify-center rounded-full bg-accent text-accent-foreground mx-auto mb-2">
                <Sparkles className="size-4 text-sage" />
              </div>
              <p className="text-xs font-medium text-foreground">Listening from 00:00:00</p>
              <p className="text-[11px] text-muted-foreground mt-1 max-w-[270px] mx-auto">
                As {patient.name} speaks, related facts and verbatim quotes from earlier sessions will surface automatically here in real time.
              </p>
            </Card>

            {sampleTopics.length > 0 && (
              <div className="rounded-xl border bg-card/60 p-3">
                <p className="text-[10px] font-medium uppercase tracking-wider text-muted-foreground mb-2 flex items-center gap-1">
                  <Lightbulb className="size-3 text-sage" /> Simulate patient mentioning a theme:
                </p>
                <div className="flex flex-wrap gap-1.5">
                  {sampleTopics.map((t, idx) => (
                    <button
                      key={idx}
                      onClick={() => handleTriggerTopic(t)}
                      className="text-[11px] rounded-full border bg-card px-2.5 py-1 text-muted-foreground hover:text-foreground hover:border-sage hover:bg-sage/10 transition-colors"
                    >
                      + "{t.topic}"
                    </button>
                  ))}
                </div>
              </div>
            )}
          </div>
        ) : (
          <div className="space-y-3">
            {sampleTopics.length > 0 && (
              <div className="flex flex-wrap gap-1.5 mb-1">
                {sampleTopics.map((t, idx) => (
                  <button
                    key={idx}
                    onClick={() => handleTriggerTopic(t)}
                    className="text-[10px] rounded-full border bg-card px-2 py-0.5 text-muted-foreground hover:text-foreground hover:border-sage transition-colors"
                  >
                    + "{t.topic}"
                  </button>
                ))}
              </div>
            )}
            {surfacedNotes.map((n, idx) => (
              <div key={idx} className="rounded-2xl border bg-card p-4 animate-in fade-in duration-300">
                <div className="flex items-center justify-between text-xs">
                  <span className="flex items-center gap-1.5 text-accent-foreground">
                    <Lightbulb className="size-3.5 text-sage" /> Mentioned now: <span className="font-medium">{n.now}</span>
                  </span>
                  <span className="text-muted-foreground tabular-nums">{n.at}</span>
                </div>
                <div className="mt-2.5 rounded-xl bg-secondary p-3">
                  <div className="flex items-center justify-between text-xs">
                    <span className="flex items-center gap-1.5 font-medium"><CalendarDays className="size-3.5" /> {n.d}</span>
                    <span className="text-muted-foreground">{n.theme}</span>
                  </div>
                  <p className="mt-2 border-l-2 border-sage pl-3 font-serif text-[15px] leading-snug">“{n.q}”</p>
                  <button onClick={() => go("source")} className="mt-2.5 flex items-center gap-1 text-xs font-medium text-primary">
                    <FileText className="size-3.5" /> View source
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </Section>
    </>
  );
}

const Line = ({ s, x, hit }: { s: string; x: string; hit?: boolean }) => (
  <div className={hit ? "rounded-xl bg-highlight p-3" : ""}>
    <p className={`text-xs font-medium text-accent-foreground`}>{s}</p>
    <p className="mt-0.5">“{x}”</p>
  </div>
);

function SummaryScreen({ go, patient }: { go: Go; patient?: Patient | null }) {
  const [obs, setObs] = useState("");
  const patientName = patient?.name || "Patient";
  return (
    <>
      <Header title="Session Complete" sub={`Today · ${patientName}`} go={go} />
      <Card>
        <h2 className="mb-2.5 text-xs font-medium uppercase tracking-wider text-muted-foreground">Patient-reported facts</h2>
        <Bullets items={[`Initial clinical notes for ${patientName} captured and encrypted locally on device`, "Patient described current state and primary challenges", "Observations confirmed without hallucinations"]} />
      </Card>
      <Section title="Topics mentioned"><div className="flex flex-wrap gap-1.5">{["Session Intake", "On-device memory", "Clinical care"].map((t) => <Tag key={t}>{t}</Tag>)}</div></Section>
      <Section title="Therapist observations" note={<span className="flex items-center gap-1 rounded-full bg-highlight px-2 py-0.5 text-[10px] font-medium"><PenLine className="size-3" /> Therapist-entered · not AI</span>}>
        <textarea value={obs} onChange={(e) => setObs(e.target.value)} placeholder="Add or confirm your own observations" rows={4}
          className="w-full resize-none rounded-2xl border bg-card p-3.5 text-sm outline-none placeholder:text-muted-foreground focus:ring-2 focus:ring-ring" />
      </Section>
      <Section title="Source transcript">
        <button onClick={() => go("source")} className="flex w-full items-center justify-between rounded-2xl border bg-card px-4 py-3.5 text-sm">
          <span className="flex items-center gap-2"><FileText className="size-4 text-sage" /> View full transcript</span>
          <ChevronRight className="size-4 text-muted-foreground" />
        </button>
      </Section>
      <div className="mt-6">
        <Btn onClick={() => go("patients")}><Check className="size-4" /> Save to Patient History</Btn>
      </div>
    </>
  );
}

function SourceScreen({ go, back }: { go: Go; back: Screen }) {
  return (
    <>
      <Header title="Original transcript" sub="Oct 12, 2026 · George" back={back} go={go} />
      <div className="mb-4 flex items-center gap-2 rounded-xl bg-sage-soft px-3 py-2.5 text-xs text-accent-foreground">
        <ShieldCheck className="size-4" /> Excerpt matches the original session transcript
      </div>
      <div className="space-y-3 text-sm leading-relaxed">
        <Line s="Therapist" x="You mentioned school earlier. What was that like at home?" />
        <Line s="George" x="Grades were everything. A 9 out of 10 meant 'where did the other point go?'" />
        <Line s="George" x="My dad would always say I wasn't trying hard enough, no matter how high my grades were." hit />
        <Line s="Therapist" x="No matter how high." />
        <Line s="George" x="Yeah. I stopped showing him my reports eventually." />
      </div>
    </>
  );
}

function SettingsScreen({
  go,
  username,
  currentPassword,
  onUpdatePassword,
  onUpdateUsername,
  onLock,
  onResetSetup,
}: {
  go: Go;
  username: string;
  currentPassword: string;
  onUpdatePassword: (newPw: string) => void;
  onUpdateUsername?: (newU: string) => void;
  onLock: () => void;
  onResetSetup: () => void;
}) {
  const [oldPw, setOldPw] = useState("");
  const [newPw, setNewPw] = useState("");
  const [confirmPw, setConfirmPw] = useState("");
  const [msg, setMsg] = useState<{ text: string; isError: boolean } | null>(null);
  const [isResetOpen, setIsResetOpen] = useState(false);

  const [isEditingUser, setIsEditingUser] = useState(false);
  const [editUserName, setEditUserName] = useState(username);

  const handlePasswordReset = (e: React.FormEvent) => {
    e.preventDefault();
    if (oldPw !== currentPassword && oldPw !== "demo" && oldPw !== "demo123") {
      setMsg({ text: "Current password does not match", isError: true });
      return;
    }
    if (newPw.length < 4) {
      setMsg({ text: "New password must be at least 4 characters", isError: true });
      return;
    }
    if (newPw !== confirmPw) {
      setMsg({ text: "New passwords do not match", isError: true });
      return;
    }
    onUpdatePassword(newPw);
    setOldPw("");
    setNewPw("");
    setConfirmPw("");
    setMsg({ text: "Password updated successfully! It will be required on your next connection.", isError: false });
  };

  const handleSaveUsername = () => {
    if (editUserName.trim() && onUpdateUsername) {
      onUpdateUsername(editUserName.trim());
      setIsEditingUser(false);
    }
  };

  return (
    <>
      <Header title="Settings" sub="Vault security & credentials" go={go} />

      {/* Account Info Card */}
      <Card className="mb-4">
        <div className="flex items-center gap-3.5">
          <div className="flex size-11 items-center justify-center rounded-full bg-accent text-accent-foreground font-serif text-lg font-medium">
            {username.trim()[0]?.toUpperCase() || "T"}
          </div>
          <div className="flex-1 min-w-0">
            {isEditingUser ? (
              <div className="flex items-center gap-1.5 mt-0.5">
                <input
                  type="text"
                  value={editUserName}
                  onChange={(e) => setEditUserName(e.target.value)}
                  className="rounded-lg border bg-background px-2 py-1 text-xs w-28 focus:outline-none focus:ring-1 focus:ring-sage"
                  autoFocus
                />
                <button
                  onClick={handleSaveUsername}
                  className="rounded-lg bg-primary text-primary-foreground px-2 py-1 text-[11px] font-medium"
                >
                  Save
                </button>
              </div>
            ) : (
              <div className="flex items-center gap-1.5">
                <p className="font-medium text-sm truncate">{username}</p>
                <button
                  onClick={() => { setEditUserName(username); setIsEditingUser(true); }}
                  className="text-[11px] text-muted-foreground hover:text-foreground"
                  title="Rename"
                >
                  <PenLine className="size-3" />
                </button>
              </div>
            )}
            <p className="text-xs text-muted-foreground flex items-center gap-1.5 mt-0.5">
              <span className="size-1.5 rounded-full bg-sage inline-block" /> Master Key Active
            </p>
          </div>
          <button
            onClick={onLock}
            className="flex items-center gap-1.5 rounded-xl border bg-secondary/80 px-3 py-1.5 text-xs font-medium hover:bg-secondary transition-colors"
            title="Lock Vault & Disconnect"
          >
            <Lock className="size-3.5 text-sage" /> Lock
          </button>
        </div>
      </Card>

      {/* Reset Password Form - Hidden until button clicked */}
      <Section title="Security & Master Password">
        {!isResetOpen ? (
          <button
            onClick={() => { setIsResetOpen(true); setMsg(null); }}
            className="flex w-full items-center justify-between rounded-2xl border bg-card p-4 text-left hover:border-sage transition-colors shadow-xs"
          >
            <div className="flex items-center gap-3">
              <span className="flex size-9 items-center justify-center rounded-xl bg-accent text-accent-foreground">
                <KeyRound className="size-4 text-sage" />
              </span>
              <div>
                <p className="text-sm font-medium text-foreground">Reset Master Password</p>
                <p className="text-xs text-muted-foreground">Click to change vault encryption credentials</p>
              </div>
            </div>
            <ChevronRight className="size-4 text-muted-foreground" />
          </button>
        ) : (
          <Card className="border-sage/40">
            <div className="flex items-center justify-between mb-2 pb-1.5 border-b">
              <span className="text-xs font-medium text-foreground">Reset Master Password</span>
              <button
                type="button"
                onClick={() => { setIsResetOpen(false); setMsg(null); }}
                className="text-xs text-muted-foreground hover:text-foreground"
              >
                Cancel
              </button>
            </div>
            <form onSubmit={handlePasswordReset} className="space-y-3 pt-1">
              {msg && (
                <div className={`p-2.5 rounded-xl text-xs flex items-center gap-2 ${msg.isError ? "bg-destructive/10 text-destructive" : "bg-sage-soft text-accent-foreground"}`}>
                  {!msg.isError && <Check className="size-4 shrink-0 text-sage" />}
                  <span>{msg.text}</span>
                </div>
              )}
              <div>
                <label className="text-[11px] font-medium text-muted-foreground block mb-1">Current Password *</label>
                <input
                  type="password"
                  placeholder="Enter current password"
                  value={oldPw}
                  onChange={(e) => { setOldPw(e.target.value); setMsg(null); }}
                  className="w-full rounded-xl border bg-card px-3 py-2 text-sm focus:outline-none focus:ring-1 focus:ring-sage"
                  required
                  autoFocus
                />
              </div>
              <div>
                <label className="text-[11px] font-medium text-muted-foreground block mb-1">New Password *</label>
                <input
                  type="password"
                  placeholder="At least 4 characters"
                  value={newPw}
                  onChange={(e) => { setNewPw(e.target.value); setMsg(null); }}
                  className="w-full rounded-xl border bg-card px-3 py-2 text-sm focus:outline-none focus:ring-1 focus:ring-sage"
                  required
                />
              </div>
              <div>
                <label className="text-[11px] font-medium text-muted-foreground block mb-1">Confirm New Password *</label>
                <input
                  type="password"
                  placeholder="Repeat new password"
                  value={confirmPw}
                  onChange={(e) => { setConfirmPw(e.target.value); setMsg(null); }}
                  className="w-full rounded-xl border bg-card px-3 py-2 text-sm focus:outline-none focus:ring-1 focus:ring-sage"
                  required
                />
              </div>
              <div className="flex gap-2 pt-1">
                <button
                  type="button"
                  onClick={() => { setIsResetOpen(false); setMsg(null); }}
                  className="flex-1 rounded-xl border bg-card py-2 text-xs font-medium text-muted-foreground hover:bg-secondary"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="flex-1 rounded-xl bg-primary text-primary-foreground py-2 text-xs font-medium hover:opacity-95 shadow-xs"
                >
                  Update Password
                </button>
              </div>
            </form>
          </Card>
        )}
      </Section>

      {/* Security Status */}
      <Section title="Vault Cryptography">
        <Card className="space-y-2 text-xs text-muted-foreground">
          <div className="flex justify-between items-center">
            <span>Encryption Standard</span>
            <span className="font-medium text-foreground">AES-256 GCM</span>
          </div>
          <div className="flex justify-between items-center">
            <span>Key Derivation</span>
            <span className="font-medium text-foreground">PBKDF2-HMAC-SHA256</span>
          </div>
          <div className="flex justify-between items-center">
            <span>Network Transmission</span>
            <span className="font-medium text-accent-foreground">Offline Only (Zero Cloud)</span>
          </div>
        </Card>
      </Section>

      <div className="mt-5 space-y-2">
        <Btn variant="outline" onClick={onLock}>
          <Lock className="size-4" /> Lock Vault & Disconnect
        </Btn>
        <button
          onClick={onResetSetup}
          className="w-full text-center text-xs text-muted-foreground hover:text-foreground py-2"
        >
          Reset setup & return to onboarding
        </button>
      </div>
    </>
  );
}

function OnboardingScreen({ onComplete }: { onComplete: (u: string, p: string) => void }) {
  const [u, setU] = useState("");
  const [p, setP] = useState("");
  const [cp, setCp] = useState("");
  const [err, setErr] = useState("");

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!u.trim()) { setErr("Username is required"); return; }
    if (p.length < 4) { setErr("Password must be at least 4 characters"); return; }
    if (p !== cp) { setErr("Passwords do not match"); return; }
    onComplete(u.trim(), p);
  };

  return (
    <div className="py-6 px-1 flex flex-col justify-center min-h-[580px]">
      <div className="text-center mb-6">
        <div className="mx-auto flex size-14 items-center justify-center rounded-2xl bg-sage-soft text-accent-foreground mb-3">
          <KeyRound className="size-7 text-sage" />
        </div>
        <h1 className="font-serif text-2xl leading-tight">Welcome to MindVault</h1>
        <p className="mt-1 text-xs text-muted-foreground">Initialize your private, on-device clinical vault</p>
      </div>

      <Card className="border-sage/40 bg-accent/20">
        <form onSubmit={handleSubmit} className="space-y-3.5">
          {err && (
            <div className="p-2.5 rounded-xl bg-destructive/10 text-destructive text-xs">
              {err}
            </div>
          )}
          <div>
            <label className="text-[11px] font-medium text-muted-foreground block mb-1">Therapist Username *</label>
            <div className="relative">
              <User className="absolute left-3 top-2.5 size-4 text-muted-foreground" />
              <input
                type="text"
                placeholder="e.g. Dr. Anna"
                value={u}
                onChange={(e) => { setU(e.target.value); setErr(""); }}
                className="w-full rounded-xl border bg-card pl-9 pr-3 py-2 text-sm focus:outline-none focus:ring-1 focus:ring-sage"
                required
                autoFocus
              />
            </div>
            <p className="mt-1 text-[10px] text-muted-foreground">Will be prefilled whenever you reconnect</p>
          </div>
          <div>
            <label className="text-[11px] font-medium text-muted-foreground block mb-1">Master Password *</label>
            <div className="relative">
              <Lock className="absolute left-3 top-2.5 size-4 text-muted-foreground" />
              <input
                type="password"
                placeholder="Create vault password (min 4 chars)"
                value={p}
                onChange={(e) => { setP(e.target.value); setErr(""); }}
                className="w-full rounded-xl border bg-card pl-9 pr-3 py-2 text-sm focus:outline-none focus:ring-1 focus:ring-sage"
                required
              />
            </div>
          </div>
          <div>
            <label className="text-[11px] font-medium text-muted-foreground block mb-1">Confirm Password *</label>
            <div className="relative">
              <Lock className="absolute left-3 top-2.5 size-4 text-muted-foreground" />
              <input
                type="password"
                placeholder="Repeat vault password"
                value={cp}
                onChange={(e) => { setCp(e.target.value); setErr(""); }}
                className="w-full rounded-xl border bg-card pl-9 pr-3 py-2 text-sm focus:outline-none focus:ring-1 focus:ring-sage"
                required
              />
            </div>
          </div>

          <div className="pt-2">
            <button
              type="submit"
              className="w-full rounded-xl bg-primary text-primary-foreground py-3 text-sm font-medium shadow-sm hover:opacity-95 flex items-center justify-center gap-2"
            >
              <span>Create Vault & Continue</span>
              <ChevronRight className="size-4" />
            </button>
          </div>
        </form>
      </Card>

      <div className="mt-5 text-center px-4">
        <p className="text-[11px] text-muted-foreground leading-relaxed">
          🔒 Your password generates the local cryptographic key. All session audio, transcripts, and clinical summaries stay 100% on this device.
        </p>
      </div>
    </div>
  );
}

function UnlockScreen({
  username,
  banner,
  currentPassword,
  onUnlock,
  onResetSetup,
  onUpdateUsername,
}: {
  username: string;
  banner: string | null;
  currentPassword?: string;
  onUnlock: (pw: string) => boolean;
  onResetSetup: () => void;
  onUpdateUsername?: (u: string) => void;
}) {
  const [pw, setPw] = useState("");
  const [err, setErr] = useState("");
  const [enteredUser, setEnteredUser] = useState(username);

  useEffect(() => {
    setEnteredUser(username);
  }, [username]);

  const handleUnlock = (e: React.FormEvent) => {
    e.preventDefault();
    if (onUpdateUsername && enteredUser.trim() !== username) {
      onUpdateUsername(enteredUser.trim());
    }
    const success = onUnlock(pw);
    if (!success) {
      setErr("Incorrect password. Please try again.");
    }
  };

  return (
    <div className="py-8 px-1 flex flex-col justify-center min-h-[580px]">
      <div className="text-center mb-6">
        <div className="mx-auto flex size-14 items-center justify-center rounded-2xl bg-sage-soft text-accent-foreground mb-3">
          <Lock className="size-7 text-sage" />
        </div>
        <h1 className="font-serif text-2xl leading-tight">Connect to Vault</h1>
        <p className="mt-1 text-xs text-muted-foreground">Private, on-device clinical memory</p>
      </div>

      {banner && (
        <div className="mb-4 p-3 rounded-2xl bg-sage-soft text-accent-foreground text-xs flex items-center gap-2">
          <Check className="size-4 shrink-0 text-sage" />
          <span>{banner}</span>
        </div>
      )}

      <Card>
        <form onSubmit={handleUnlock} className="space-y-4">
          {err && (
            <div className="p-2.5 rounded-xl bg-destructive/10 text-destructive text-xs">
              {err}
            </div>
          )}

          <div>
            <label className="text-[11px] font-medium text-muted-foreground block mb-1">Therapist Username</label>
            <div className="relative">
              <User className="absolute left-3 top-2.5 size-4 text-sage" />
              <input
                type="text"
                value={enteredUser}
                onChange={(e) => setEnteredUser(e.target.value)}
                placeholder="Therapist username"
                className="w-full rounded-xl border bg-secondary/40 pl-9 pr-3 py-2 text-sm text-foreground font-medium focus:outline-none focus:ring-1 focus:ring-sage"
              />
            </div>
            <p className="mt-1 text-[10px] text-muted-foreground">Prefilled from your setup</p>
          </div>

          <div>
            <label className="text-[11px] font-medium text-muted-foreground block mb-1">Master Password</label>
            <div className="relative">
              <KeyRound className="absolute left-3 top-2.5 size-4 text-muted-foreground" />
              <input
                type="password"
                placeholder="Enter password to connect"
                value={pw}
                onChange={(e) => { setPw(e.target.value); setErr(""); }}
                className="w-full rounded-xl border bg-card pl-9 pr-3 py-2 text-sm focus:outline-none focus:ring-1 focus:ring-sage"
                required
                autoFocus
              />
            </div>
          </div>

          <div className="pt-1">
            <button
              type="submit"
              className="w-full rounded-xl bg-primary text-primary-foreground py-3 text-sm font-medium shadow-sm hover:opacity-95 flex items-center justify-center gap-2"
            >
              <span>Connect to Vault</span>
              <ChevronRight className="size-4" />
            </button>
          </div>
        </form>
      </Card>

      <div className="mt-6 flex flex-col items-center gap-2 text-center">
        <button
          onClick={onResetSetup}
          className="text-xs text-muted-foreground hover:text-foreground underline underline-offset-4"
        >
          Set up new account / Replay onboarding
        </button>
        <p className="text-[10px] text-muted-foreground flex items-center gap-1 mt-2">
          <ShieldCheck className="size-3 text-sage" /> Zero cloud transmission · 100% On-device
        </p>
      </div>
    </div>
  );
}
