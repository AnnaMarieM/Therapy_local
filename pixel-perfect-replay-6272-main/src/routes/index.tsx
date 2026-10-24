import { createFileRoute } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import {
  Users, Mic, Settings, ChevronLeft, ChevronRight, ChevronDown, FileText, Lock,
  ShieldCheck, Pause, Play, Square, Plus, Check, CalendarDays, Lightbulb, HardDrive,
  Trash2, PenLine,
} from "lucide-react";

export const Route = createFileRoute("/")({
  head: () => ({
    meta: [
      { title: "MindVault — Private, verifiable memory for therapists" },
      { name: "description", content: "Prototype of MindVault: offline recording, factual summaries and source-linked recall for psychotherapists." },
      { property: "og:title", content: "MindVault — Private, verifiable memory for therapists" },
      { property: "og:description", content: "The AI remembers and retrieves. The therapist interprets." },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary_large_image" },
    ],
  }),
  component: App,
});

type Screen = "patients" | "profile" | "live" | "summary" | "source" | "settings" | "session" | "prepare" | "details";
type Go = (s: Screen) => void;

function App() {
  const [screen, setScreen] = useState<Screen>("patients");
  const [prev, setPrev] = useState<Screen>("patients");
  const go: Go = (s) => { setPrev(screen); setScreen(s); };
  return (
    <main className="min-h-screen flex items-center justify-center sm:py-8">
      <div className="relative w-full sm:w-[400px] h-screen sm:h-[840px] bg-background sm:rounded-[2.5rem] sm:border-8 sm:border-foreground/90 overflow-hidden flex flex-col">
        <div className="flex items-center justify-between px-6 pt-3 pb-1 text-xs text-muted-foreground">
          <span className="font-medium">13:58</span>
          <span className="flex items-center gap-1"><Lock className="size-3" /> MindVault</span>
        </div>
        <div key={screen} className="flex-1 overflow-y-auto px-5 pb-8">
          {screen === "patients" && <PatientsScreen go={go} />}
          {screen === "profile" && <ProfileScreen go={go} />}
          {screen === "session" && <SessionDetailScreen go={go} />}
          {screen === "prepare" && <PrepareScreen go={go} />}
          {screen === "details" && <DetailsScreen go={go} />}
          {screen === "live" && <LiveScreen go={go} />}
          {screen === "summary" && <SummaryScreen go={go} />}
          {screen === "source" && <SourceScreen go={go} back={prev} />}
          {screen === "settings" && <SettingsScreen go={go} />}
        </div>
        <NavBar screen={screen} go={go} />
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

const others = [
  { i: "M", name: "Marta", age: 29, last: "Oct 22, 2026", themes: ["Grief", "Sleep"] },
  { i: "D", name: "Daniel", age: 35, last: "Oct 19, 2026", themes: ["Transitions", "Social anxiety"] },
];

function PatientsScreen({ go }: { go: Go }) {
  return (
    <>
      <Header title="Patients" sub="Private longitudinal memory for your therapy practice" go={go} />
      <PrivacyPill />
      <div className="mt-6 space-y-3">
        <button onClick={() => go("profile")} className="block w-full text-left">
          <PatientCard i="G" name="George" age={41} last="Oct 24, 2026" themes={["Work burnout", "Perfectionism", "Family expectations"]} />
        </button>
        {others.map((p) => <PatientCard key={p.name} {...p} />)}
        <button className="flex w-full items-center justify-center gap-2 rounded-2xl border border-dashed py-4 text-sm text-muted-foreground">
          <Plus className="size-4" /> Add patient
        </button>
      </div>
    </>
  );
}

function PatientCard({ i, name, age, last, themes }: { i: string; name: string; age: number; last: string; themes: string[] }) {
  return (
    <Card>
      <div className="flex items-center gap-3">
        <div className="flex size-11 items-center justify-center rounded-full bg-accent font-serif text-lg text-accent-foreground">{i}</div>
        <div className="flex-1">
          <p className="font-medium">{name}</p>
          <p className="text-xs text-muted-foreground">Age {age} · Last session: {last}</p>
        </div>
        <ChevronRight className="size-4 text-muted-foreground" />
      </div>
      <div className="mt-3 flex flex-wrap gap-1.5">{themes.map((t) => <Tag key={t}>{t}</Tag>)}</div>
    </Card>
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

function ProfileScreen({ go }: { go: Go }) {
  return (
    <>
      <header className="pt-3 pb-7">
        <button onClick={() => go("patients")} className="-ml-1 mb-4 flex items-center text-sm text-muted-foreground"><ChevronLeft className="size-4" /> Patients</button>
        <div className="flex items-center gap-4">
          <div className="flex size-14 items-center justify-center rounded-full bg-accent font-serif text-2xl text-accent-foreground">G</div>
          <div>
            <h1 className="font-serif text-[30px] leading-tight">George</h1>
            <p className="text-sm text-muted-foreground">41 · Senior Auditor</p>
          </div>
        </div>
        <p className="mt-3 text-xs text-muted-foreground">Last session: Oct 24, 2026</p>
      </header>

      <button onClick={() => go("live")} className="flex w-full flex-col items-start rounded-3xl bg-primary p-6 text-left text-primary-foreground">
        <span className="flex size-12 items-center justify-center rounded-full bg-primary-foreground/15"><Mic className="size-6" /></span>
        <span className="mt-6 font-serif text-2xl">Start New Session</span>
        <span className="mt-1 text-sm opacity-80">Records and transcribes on this device</span>
      </button>

      <div className="mt-3 grid grid-cols-2 gap-3">
        {[{ s: "prepare" as Screen, icon: CalendarDays, t: "Prepare for Session", d: "What matters before he walks in" }, { s: "details" as Screen, icon: Users, t: "Patient Profile", d: "Background and history" }].map(({ s, icon: I, t, d }) => (
          <button key={t} onClick={() => go(s)} className="flex flex-col items-start rounded-2xl border bg-card p-4 text-left">
            <I className="size-5 text-sage" />
            <span className="mt-5 text-sm font-medium">{t}</span>
            <span className="mt-0.5 text-xs text-muted-foreground">{d}</span>
          </button>
        ))}
      </div>

      <Section title="Most recent session">
        <button onClick={() => go("session")} className="flex w-full items-center justify-between rounded-2xl border bg-card px-4 py-3.5 text-left text-sm">
          <span><span className="text-muted-foreground">Oct 24 · </span>Work criticism and feelings of failure</span>
          <ChevronRight className="size-4 shrink-0 text-muted-foreground" />
        </button>
      </Section>
      <div className="mt-6 flex items-center justify-center gap-2 text-xs text-muted-foreground"><Lock className="size-3.5" /> Stored only on this device</div>
    </>
  );
}

function PrepareScreen({ go }: { go: Go }) {
  return (
    <>
      <Header title="Prepare for Session" sub="George · what to remember before he walks in" back="profile" go={go} />
      <section className="rounded-2xl bg-secondary p-4">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="font-serif text-xl">Where We Left Off</h2>
          <span className="text-xs text-muted-foreground">Oct 24</span>
        </div>
        <Bullets items={["Manager criticism triggered strong feelings of failure", "Difficulty switching off from work", "Increased tension with Laura", "Anxiety around discussions about having children"]} />
      </section>
      <Section title="Recent Context"><Chips items={["Work burnout", "Criticism", "Perfectionism", "Marriage", "Life direction"]} /></Section>
      <Section title="Relevant Past Memories" note={<span className="text-[11px] text-muted-foreground">From prior transcripts</span>}>
        <div className="space-y-2">
          {[["Oct 12, 2026", "George described feeling that good performance was “never enough” when discussing his father's expectations."], ["Sep 21, 2026", "Said he was unsure whether he wanted children or was afraid of making the wrong choice."]].map(([d, t]) => (
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

function DetailsScreen({ go }: { go: Go }) {
  return (
    <>
      <Header title="Patient Profile" sub="George · stable, long-term context" back="profile" go={go} />
      <Card>
        <h2 className="mb-2.5 text-xs font-medium uppercase tracking-wider text-muted-foreground">Background</h2>
        <Bullets items={["41 years old", "Senior auditor", "Married to Laura", "Dog named Milo", "Long-term burnout and dissatisfaction"]} />
      </Card>
      <Section title="Important People">
        <div className="grid grid-cols-2 gap-2">
          {[["Laura", "Wife"], ["Father", "Family"], ["Boss", "Work"], ["Milo", "Dog"]].map(([n, r]) => (
            <div key={n} className="rounded-xl border bg-card px-3 py-2.5"><p className="text-sm font-medium">{n}</p><p className="text-xs text-muted-foreground">{r}</p></div>
          ))}
        </div>
      </Section>
      <Section title="Recurring Themes"><Chips items={["Work burnout", "Perfectionism", "Fear of disappointing others", "Criticism", "Relationship distance", "Life direction"]} /></Section>
      <Section title="Session History"><HistoryList go={go} /></Section>
    </>
  );
}

function SessionDetailScreen({ go }: { go: Go }) {
  return (
    <>
      <Header title="Oct 12, 2026" sub="George · Father's expectations and achievement pressure" back="details" go={go} />
      <Card>
        <h2 className="mb-2.5 text-xs font-medium uppercase tracking-wider text-muted-foreground">Factual summary</h2>
        <Bullets items={["George talked about school and his father's reaction to grades", "Said good performance was “never enough” at home", "Mentioned he stopped showing his father his reports"]} />
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

function LiveScreen({ go }: { go: Go }) {
  const [secs, setSecs] = useState(1394);
  const [paused, setPaused] = useState(false);
  useEffect(() => {
    if (paused) return;
    const id = setInterval(() => setSecs((s) => s + 1), 1000);
    return () => clearInterval(id);
  }, [paused]);
  const fmt = (n: number) => String(n).padStart(2, "0");
  return (
    <>
      <Header title="George · Live Session" go={go} />
      <div className="rounded-2xl bg-sage-soft p-4 text-sm">
        <p className="flex items-center gap-2.5 font-medium text-accent-foreground"><Dot /> Recording Locally</p>
        <p className="mt-2 font-medium">Processing locally on this device</p>
        <p className="text-xs text-muted-foreground">No audio or transcript leaves the phone.</p>
        <p className="mt-2.5 flex items-center gap-1.5 text-xs text-accent-foreground"><Check className="size-3.5" /> Patient consent confirmed</p>
      </div>

      <Card className="mt-4 py-6 text-center">
        <div className="flex h-12 items-center justify-center gap-1">
          {bars.map((h, i) => (
            <span key={i} className={`w-1 rounded-full ${paused ? "bg-border" : "bg-sage"}`} style={{ height: paused ? 4 : h + 4 }} />
          ))}
        </div>
        <p className="mt-4 font-serif text-4xl tabular-nums">{fmt(Math.floor(secs / 3600))}:{fmt(Math.floor(secs / 60) % 60)}:{fmt(secs % 60)}</p>
        <p className="text-xs text-muted-foreground">{paused ? "Paused" : "Listening on device"}</p>
        <div className="mt-5 grid grid-cols-2 gap-2.5">
          <Btn variant="outline" onClick={() => setPaused(!paused)}>{paused ? <><Play className="size-4" /> Resume</> : <><Pause className="size-4" /> Pause</>}</Btn>
          <Btn variant="danger" onClick={() => go("summary")}><Square className="size-4" /> End Session</Btn>
        </div>
      </Card>

      <Section title="Notes from past sessions" note={<span className="text-[11px] text-muted-foreground">Appear when a topic recurs</span>}>
        <div className="space-y-3">
          {[
            { at: "23:02", now: "Boss criticizing his work", d: "Oct 12, 2026", theme: "Criticism and achievement", q: "My dad would always say I wasn't trying hard enough, no matter how high my grades were." },
            { at: "18:40", now: "Laura", d: "Sep 21, 2026", theme: "Marriage", q: "Laura asked me if I'm unhappy with her. I didn't know what to say." },
          ].map((n) => (
            <div key={n.at} className="rounded-2xl border bg-card p-4">
              <div className="flex items-center justify-between text-xs">
                <span className="flex items-center gap-1.5 text-accent-foreground"><Lightbulb className="size-3.5 text-sage" /> Mentioned now: <span className="font-medium">{n.now}</span></span>
                <span className="text-muted-foreground tabular-nums">{n.at}</span>
              </div>
              <div className="mt-3 rounded-xl bg-secondary p-3.5">
                <div className="flex items-center justify-between text-xs">
                  <span className="flex items-center gap-1.5 font-medium"><CalendarDays className="size-3.5" /> {n.d}</span>
                  <span className="text-muted-foreground">{n.theme}</span>
                </div>
                <p className="mt-2 border-l-2 border-sage pl-3 font-serif text-[16px] leading-snug">“{n.q}”</p>
                <button onClick={() => go("source")} className="mt-3 flex items-center gap-1 text-xs font-medium text-primary"><FileText className="size-3.5" /> View source</button>
              </div>
            </div>
          ))}
        </div>
        <p className="mt-3 text-xs text-muted-foreground">Shown for your reference only. You decide whether it is clinically meaningful.</p>
      </Section>
    </>
  );
}

function MemoryEvidence({ go }: { go: Go }) {
  return (
    <div className="mt-3 rounded-xl bg-secondary p-3.5">
      <div className="flex items-center justify-between text-xs">
        <span className="flex items-center gap-1.5 font-medium"><CalendarDays className="size-3.5" /> Oct 12, 2026</span>
        <span className="text-muted-foreground">Theme: Criticism and achievement</span>
      </div>
      <p className="mt-2.5 text-xs text-muted-foreground">Source excerpt</p>
      <p className="mt-1 border-l-2 border-sage pl-3 font-serif text-[16px] leading-snug">“My dad would always say I wasn't trying hard enough, no matter how high my grades were.”</p>
      <div className="mt-3 flex items-center justify-between">
        <span className="text-[11px] text-muted-foreground">Retrieved from prior session transcript</span>
        <button onClick={() => go("source")} className="flex items-center gap-1 text-xs font-medium text-primary"><FileText className="size-3.5" /> View source</button>
      </div>
    </div>
  );
}

const Line = ({ s, x, hit }: { s: string; x: string; hit?: boolean }) => (
  <div className={hit ? "rounded-xl bg-highlight p-3" : ""}>
    <p className={`text-xs font-medium ${s === "George" ? "text-accent-foreground" : "text-muted-foreground"}`}>{s}</p>
    <p className="mt-0.5">“{x}”</p>
  </div>
);

function SummaryScreen({ go }: { go: Go }) {
  const [obs, setObs] = useState("");
  return (
    <>
      <Header title="Session Complete" sub="Oct 24, 2026 · George" go={go} />
      <Card>
        <h2 className="mb-2.5 text-xs font-medium uppercase tracking-wider text-muted-foreground">Patient-reported facts</h2>
        <Bullets items={["Manager criticized George's report this week", "George described shutting down after the criticism", "He said he felt “not good enough”", "He and Laura argued after he stayed late at work twice this week"]} />
      </Card>
      <Section title="People mentioned"><div className="flex flex-wrap gap-1.5">{["Laura", "Boss", "Father"].map((t) => <Tag key={t}>{t}</Tag>)}</div></Section>
      <Section title="Topics mentioned"><div className="flex flex-wrap gap-1.5">{["Work", "Criticism", "Burnout", "Marriage", "Self-worth"].map((t) => <Tag key={t}>{t}</Tag>)}</div></Section>
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
      <div className="mt-6"><PrivacyPill title="Stored locally on this device" sub="Processing locally · No patient data leaves the phone" /></div>
      <div className="mt-6 space-y-2.5">
        <Btn onClick={() => go("profile")}><Check className="size-4" /> Save Session</Btn>
        <Btn variant="danger" onClick={() => go("profile")}><Trash2 className="size-4" /> Delete Session</Btn>
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

function SettingsScreen({ go }: { go: Go }) {
  const rows = [
    { icon: HardDrive, t: "Local storage", s: "1.2 GB used · encrypted on device" },
    { icon: ShieldCheck, t: "Privacy", s: "Offline mode · no network access" },
    { icon: Check, t: "Consent preferences", s: "Confirm consent before every recording" },
  ];
  return (
    <>
      <Header title="Settings" go={go} />
      <PrivacyPill />
      <div className="mt-6 divide-y rounded-2xl border bg-card">
        {rows.map(({ icon: I, t, s }) => (
          <div key={t} className="flex items-center gap-3 px-4 py-3.5">
            <I className="size-4 text-sage" />
            <div className="flex-1"><p className="text-sm font-medium">{t}</p><p className="text-xs text-muted-foreground">{s}</p></div>
            <ChevronRight className="size-4 text-muted-foreground" />
          </div>
        ))}
      </div>
      <div className="mt-6"><Btn variant="danger"><Trash2 className="size-4" /> Delete all local data</Btn></div>
    </>
  );
}
