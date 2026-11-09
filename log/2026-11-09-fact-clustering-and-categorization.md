# Záznam implementace: Shlukování a kategorizace faktů (Fact Clustering & Categorization)

**Datum:** 2026-11-09  
**Větev:** `feature/fact-clustering-and-categorization`  
**Autor:** Antigravity AI Assistant  

---

## 1. Kontext a motivace (Přepis Martin & Anička)

Z rozhovoru Martina a Aničky vyplynuly klíčové požadavky na práci s fakty a strukturu klientského profilu:
1. **Rozlišení transientních a trvalých faktů**:
   - **Session fakta (`scope: "session"`)**: Pomíjivá, situační fakta vázaná na konkrétní sezení (např. momentální nálada, počasí a jak působí na sezení, akutní událost týdne).
   - **Profile fakta (`scope: "profile"`)**: Dlouhodobě platná fakta formující klientský profil (např. místo narození, rodinné vztahy, chronické vzorce, formativní životní události).
2. **Čtyři základní entity**:
   - *Last session summary* (sumář posledního sezení).
   - *Profile summary* (globální sumář klienta).
   - *Session facts* (fakta z posledního sezení).
   - *Profile facts* (dlouhodobá klientská fakta).
3. **Problém pojmenování a stabilizace kategorií**:
   - V prvních sezeních (1.–3. sezení) se kategorie přirozeně objevují a rozšiřují, jak klient otevírá klíčová životní témata.
   - Následně se taxonomický prostor stabilizuje a většina nových faktů spadá do existujících témat.
   - Systém však **musí** umožnit vznik nové kategorie i po 4. sezení, pokud klient otevře zcela nové, dosud neřešené téma.
   - Je nutné zamezit synonymnímu chaosu a fragmentaci (aby LLM nevytvářelo synonyma jako *Práce*, *Zaměstnání*, *Kariéra*, *Job* jako separátní kategorie).

---

## 2. Zvolený ML model a shlukovací algoritmus

Vzhledem k přísné architektuře aplikace (100% on-device, offline provoz, ochrana soukromí, nulové volání cloudu):

1. **Vektorový model (Embedding model)**:
   - Využívá se lokálně integrovaný on-device model **EmbeddingGemma 2** (prostřednictvím LiteRT / MediaPipe GenAI).
   - Poskytuje vysoce kvalitní 768-dimenzionální husté vektorové reprezentace optimalizované pro sémantickou podobnost v češtině i angličtině.

2. **Standardizovaná kanonická taxonomie (`CategoryTaxonomy.kt`)**:
   - Definuje 8 kanonických psychoterapeutických kategorií (pro češtinu i angličtinu) s bohatými kotevními popisy (anchor texts):
     - *Rodina a vztahy* (*Family & Relationships*)
     - *Práce a kariéra* (*Work & Career*)
     - *Zdraví, tělo a spánek* (*Health, Body & Sleep*)
     - *Nálada a emoce* (*Mood & Emotions*)
     - *Historie a události* (*History & Life Events*)
     - *Závislosti a návyky* (*Addictions & Habits*)
     - *Zdroje a zvládání* (*Coping & Strengths*)
     - *Osobní témata a sebepojetí* (*Self-concept & Identity*)

3. **Adaptivní dvoufázové přiřazování a shlukování (`CategoryClusterer.kt`)**:
   - **Fáze 1 (Aktivní centroidy klienta)**: Spočítá se normalizovaný centroid pro každou již existující kategorii klienta. Pokud je kosinová podobnost nového faktu s existujícím centroidem $\ge 0.65$, fakt se přiřadí do této kategorie. Tím se spolehlivě zabrání tvorbě synonym.
   - **Fáze 2 (Aktivace z kanonické taxonomie)**: Pokud fakt neodpovídá žádné aktivní kategorii klienta, porovná se s kotevními embeddingy neaktivovaných kanonických kategorií. Při podobnosti $\ge 0.60$ se odemkne příslušná kanonická kategorie (funguje kdykoli, jak v 1., tak v 5. sezení).
   - **Fáze 3 (Hierarchické shlukování – Agglomerative Clustering)**: Pro nezařazená specifická témata provádí algoritmus hierarchické shlukování (Average Linkage, práh vzdálenosti 0.35) a jako název kategorie volí medoid (fakt s nejvyšší průměrnou podobností k ostatním v daném shluku).

---

## 3. Přehled provedených změn v kódu

### 3.1. Databáze a perzistence (`MindStore.kt`)
- Navýšena verze schématu SQLite / SQLCipher z `3` na `4` (`VERSION = 4`).
- V tabulce `facts` přidány sloupce:
  - `scope TEXT NOT NULL DEFAULT 'profile'`
  - `category TEXT NOT NULL DEFAULT ''`
- Doplněna migrace v `onUpgrade`:
  ```sql
  ALTER TABLE facts ADD COLUMN scope TEXT NOT NULL DEFAULT 'profile'
  ALTER TABLE facts ADD COLUMN category TEXT NOT NULL DEFAULT ''
  ```
- Přidána metoda `factEmbeddings(client: String): List<Pair<FactRow, FloatArray>>` pro načítání faktů včetně jejich vektorových embeddingů pro výpočet shluků a centroidů.
- Aktualizována metoda `factOf` a indexy pro správné načítání sloupce s embeddingem.

### 3.2. Doménový model a parsování faktů (`Facts.kt`, `FactParser.kt`)
- Třída `Fact` rozšířena o parametry `scope: String = SCOPE_PROFILE` a `category: String = ""`.
- Zavedeny konstanty `SCOPE_PROFILE = "profile"` a `SCOPE_SESSION = "session"`.
- Aktualizována JSON serializace (`toJson()`) i deserializace (`fromJson()`).
- Striktní schéma `EXTRACT_STRICT` i tolerantní regulární parser v `FactParser` upraveny pro rozpoznání klíčů `scope` / `rozsah` a `category` / `kategorie`.
- Doplněna normalizace a fallback pro nevalidní hodnoty.

### 3.3. Prompty pro extrakci faktů (`demo/prompts/cs/extract.system.txt`, `demo/prompts/extract.system.txt`)
- Prompty doplněny o explicitní instrukce:
  - Rozlišovat `scope`: `"profile"` pro dlouhodobé charakteristiky a `"session"` pro momentální stav či průběh sezení.
  - Generovat `category` odpovídající standardní psychoterapeutické taxonomii.

### 3.4. Pipeline zpracování sezení (`Pipeline.kt`)
- Během commit fáze sezení (`commit()`):
  - Načtou se existující embeddingy klienta z `MindStore.factEmbeddings(client)`.
  - Spočítají se vektorové centroidy pro jednotlivé kategorie.
  - Každému novému faktu se pomocí `CategoryClusterer.assignCategory` přiřadí stabilizovaná kategorie s ohledem na jazyk sezení (`PromptLanguage`).

### 3.5. UI klientského profilu (`ProfileSheet.kt`, `PatientData.kt`, `ClientRepository.kt`)
- `FactItem` rozšířen o `scope` a `category`.
- V `ClientRepository.kt` upraven mapping z databázové `FactRow` na UI model.
- V `ProfileSheet.kt`:
  - Přidán interaktivní horizontální pruh filtrů (`LazyRow` s `CategoryChip`) zobrazující všechny kategorie klienta s počty faktů.
  - Možnost přepínat filtry (Vše / jednotlivé kategorie).
  - V položkách `FactRow` zobrazen odznak kategorie a u session faktů vizuální štítek *"Sezení"* odlišující transientní informace.

---

## 4. Vytvořené testy a verifikace

1. **`CategoryClustererTest.kt`**:
   - Ověření normalizace a vyhledávání v kanonické taxonomii (diakritika, velikost písmen, spojky).
   - Test normalizovaného výpočtu centroidu.
   - Test sloučení synonyma do aktivního centroidu klienta (prevence duplicit).
   - Test odemčení nové kanonické kategorie z kotevních popisů (sezení 4+).
   - Test hierarchického shlukování (Agglomerative clustering) a volby medoidu.
2. **`FactParserTest.kt`**:
   - Doplněny testy parsování polí `scope` a `category` ze striktního JSONu i volného formátu.
3. **Výsledky běhu unit testů**:
   - Všechny testy v balíčcích `com.therapylocal.app.facts.*`, `com.therapylocal.app.jobs.*`, `com.therapylocal.app.engine.runner.*` a `com.therapylocal.app.vault.*` proběhly úspěšně (`BUILD SUCCESSFUL`).

