# Magic Touch Remove

App Android (Kotlin + Jetpack Compose, Material 3) per **rimuovere oggetti, persone e ombre dalle foto con l'intelligenza artificiale**, nello stile di TouchRetouch.
Tutto gira **sul telefono**: le foto non vengono mai inviate a nessun server.

[![Ultima versione](https://img.shields.io/github/v/release/marco90-cyber/Magic-touch-remove?label=APK)](https://github.com/marco90-cyber/Magic-touch-remove/releases/latest)
[![Licenza: MIT](https://img.shields.io/badge/licenza-MIT-green.svg)](LICENSE)

## Indice
- [Screenshot](#screenshot)
- [Funzioni](#funzioni)
- [Installazione](#installazione)
- [Guida all'uso](#guida-alluso)
- [I modelli di intelligenza artificiale](#i-modelli-di-intelligenza-artificiale)
- [Impostazioni](#impostazioni)
- [Risoluzione dei problemi e registro eventi](#risoluzione-dei-problemi-e-registro-eventi)
- [Build e aggiornamenti](#build-e-aggiornamenti)
- [Aggiornare i modelli](#aggiornare-i-modelli)
- [Licenze](#licenze)

## Screenshot
> _Gli screenshot arriveranno presto._ Le immagini andranno nella cartella [`docs/screenshots`](docs/screenshots).

| Home | Editor | Selezione automatica | Impostazioni |
|---|---|---|---|
| _in arrivo_ | _in arrivo_ | _in arrivo_ | _in arrivo_ |

## Funzioni

**Strumenti di selezione**
- **Selezione automatica**: tocca un oggetto e l'IA ne trova il contorno (MobileSAM o SAM 2, a scelta). Disegna un riquadro per oggetti difficili.
  - *Pixel in più* (0 / 5 / 10 / 15): allarga la selezione oltre il bordo, con anteprima in tempo reale.
  - *Includi ombra*: aggiunge alla selezione l'ombra dell'oggetto (vedi [la guida](#selezionare-anche-lombra)).
  - *Durezza*: bordo netto o sfumato, perché il riempimento si fonda con la foto.
- **Pennello**: dipingi l'oggetto da eliminare. Dimensione, durezza e opacità regolabili.
- **Lasso**: disegna un contorno libero attorno all'oggetto.
- **Gomma**: cancella parte della selezione.
- **Ripristina**: rimette i pixel originali dove l'IA ha toccato troppo.

**Rimozione con l'IA**
- **Cancella** con il tasto dedicato, oppure modalità **AUTO** che parte appena alzi il dito.
- Più modelli tra cui scegliere, dal più veloce al più creativo (vedi [i modelli](#i-modelli-di-intelligenza-artificiale)).
- Per la massima precisione l'app ritaglia la zona attorno alla maschera, la ricostruisce a 512×512 e reincolla il risultato sulla foto sostituendo solo i pixel selezionati.
- **IA generativa** (Stable Diffusion 1.5, sperimentale): ricostruisce lo sfondo con dettagli realistici; puoi anche descrivere cosa mettere al posto.

**Comodità**
- **Lente d'ingrandimento** adattiva mentre disegni; zoom e spostamento a due dita, mantenuti quando apri i pannelli.
- Tieni premuto su **Pennello** o **Gomma** per cambiare la dimensione con un'anteprima; tieni premuto su **Selezione** per le opzioni di selezione.
- **Annulla / Ripeti** e **confronto** con la foto originale.
- **Salvataggio a piena risoluzione**: la foto originale resta intatta, solo le zone modificate vengono sostituite. Scegli cartella, formato (JPEG / PNG / WebP), qualità (80–100) e dimensione; vedi la stima del peso del file.
- Tema **chiaro / scuro / come il sistema**.
- **Aggiornamenti** dell'app e dei modelli direttamente dall'app.
- **Registro eventi** salvabile, per segnalare problemi.

## Installazione
1. Apri la pagina delle [**Releases**](https://github.com/marco90-cyber/Magic-touch-remove/releases/latest) e scarica l'APK più recente.
2. Aprilo sul telefono e consenti l'installazione da questa fonte, se Android lo chiede.
3. Al primo avvio l'app propone di scaricare un modello IA (serve la connessione; poi tutto funziona offline).

**Requisiti**: Android 10 o superiore (API 29), processore a 64 bit (arm64). Per l'IA generativa servono inoltre almeno **8 GB di RAM**, circa 2 GB di spazio libero e un telefono potente (vedi sotto).

Gli aggiornamenti successivi si installano dall'app (Impostazioni › Aggiornamenti): modelli e impostazioni restano.

## Guida all'uso

### Rimuovere un oggetto (il modo più veloce)
1. Dalla **Home** tocca il pulsante centrale e scegli una foto dalla galleria.
2. Scegli lo strumento **Selezione** e **tocca l'oggetto**. L'app lo evidenzia.
3. Se la modalità **AUTO** è attiva la rimozione parte subito; altrimenti premi **Cancella**.
4. Non sei soddisfatto? **Annulla**, oppure usa **Ripristina** sulle parti che non andavano toccate.

### Selezionare a mano (Pennello, Lasso, Gomma)
- **Pennello**: dipingi sopra l'oggetto. Tieni premuto sul tasto per cambiare la dimensione trascinando.
- **Lasso**: traccia il contorno attorno a un oggetto grande; la zona interna viene selezionata.
- **Gomma**: cancella un pezzo di selezione fatto per errore.
- Pizzica con due dita per **ingrandire** e fai scorrere per spostarti; la lente ti mostra il dettaglio sotto il dito.

### Rifinire la selezione automatica
Tieni premuto sul tasto **Selezione** per aprire il pannello:
- **Pixel in più**: se restano contorni dell'oggetto dopo la rimozione, allarga la selezione di 5–15 pixel.
- **Durezza**: abbassala per sfumare il bordo del riempimento.

### Selezionare anche l'ombra
Nel pannello di **Selezione** muovi il cursore **Includi ombra**: l'app aggiunge alla selezione le zone vicine all'oggetto che sono più scure ma dello stesso colore del terreno.
Funziona meglio su superfici semplici (asfalto, pavimento, muro, sabbia); su sfondi molto colorati o scene molto scure regola il cursore o seleziona l'ombra con il Pennello.

### Usare l'IA generativa
1. In **Impostazioni › Modelli IA** scarica e scegli *Stable Diffusion 1.5* (o la versione *LCM*, più veloce).
2. Nell'editor apri il pannello **Impostazioni** (icona dei cursori): compare il campo **«Cosa metto al posto?»**.
3. **Lascialo vuoto** per ricostruire lo sfondo: prima LaMa riempie il buco con uno sfondo coerente, poi Stable Diffusion lo rifinisce con texture e dettaglio, senza inventare oggetti nuovi.
4. **Scrivi un testo** (meglio in inglese, per esempio `green grass`, `plain white wall`) per generare qualcosa di preciso al posto dell'oggetto.
5. Un'elaborazione richiede da decine di secondi a qualche minuto; puoi **annullarla**.

### Salvare la foto
Tocca l'icona di salvataggio in alto e scegli **cartella, formato, qualità e dimensione**. L'app salva alla risoluzione dell'originale lasciando intatti i pixel che non hai modificato.

### Scegliere i modelli, il tema e gli aggiornamenti
Dalla Home apri l'ingranaggio **Impostazioni**:
- **Aggiornamenti**: nuove versioni dell'app e dei modelli.
- **Modelli IA**: scegli, scarica o elimina i modelli; interruttore NPU per l'IA generativa.
- **Tema**: chiaro, scuro o come il sistema.
- **Registro eventi** e **Informazioni**.

## I modelli di intelligenza artificiale
I modelli non sono nell'APK: si scaricano dall'app (con verifica SHA-256) e si possono eliminare in qualsiasi momento.

| Modello | Uso | Peso | Note |
|---|---|---|---|
| **LaMa** | Rimozione oggetti (predefinito) | ~200 MB | Ottimo equilibrio qualità/velocità. |
| **MI-GAN** | Rimozione oggetti | ~28 MB | Molto leggero e veloce. |
| **MobileSAM** | Selezione automatica | ~45 MB | Veloce, ideale per iniziare. |
| **SAM 2 Tiny** | Selezione automatica | ~155 MB | Più preciso sui bordi, più lento. |
| **Stable Diffusion 1.5 Inpainting** | IA generativa | ~2,1 GB | Sperimentale. Richiede un telefono potente (≥ 8 GB di RAM). |
| **Stable Diffusion 1.5 LCM** | IA generativa veloce | ~2,1 GB | Sperimentale. 4 passaggi invece di 20, qualità leggermente inferiore. |

Note sull'IA generativa:
- È **sperimentale**: non è il modello predefinito, e i tempi dipendono molto dal telefono.
- Un'eventuale **NPU** può accelerarla (interruttore nelle impostazioni dei modelli), ma non tutti i telefoni la supportano bene.
- I file in comune tra le due versioni (testo, codec dell'immagine) si scaricano una sola volta.

## Impostazioni
- **Aggiornamenti**: controlla le nuove versioni dell'app (si installa sopra l'esistente) e dei modelli.
- **Modelli IA**: un modello attivo per ogni funzione (rimozione, selezione); mostra spazio occupato, download con avanzamento, eliminazione singola o totale.
- **Tema**: chiaro, scuro o come il sistema. L'editor delle foto resta sempre scuro per giudicare meglio i colori.
- **Registro eventi**: vedi sotto.
- **Informazioni**: versione, sviluppatori, privacy e crediti.

## Risoluzione dei problemi e registro eventi
- **L'app si chiude con l'IA generativa**: succede quando manca memoria (Android chiude l'app). Prova la versione LCM, chiudi le altre app oppure usa LaMa / MI-GAN.
- **Il risultato inventa un oggetto**: lascia vuoto il campo del testo (modalità «ricostruisci»), oppure aumenta *Pixel in più* per coprire bordi e ombre.
- **Restano ombre o contorni**: usa *Includi ombra* e *Pixel in più*.
- **Segnalare un problema**: Impostazioni › **Registro eventi** › *Salva il registro in un file…*, poi allega il file a una [issue](https://github.com/marco90-cyber/Magic-touch-remove/issues). Il registro contiene modello del telefono, RAM, passaggi dell'IA ed errori: **nessuna foto**.

## Build e aggiornamenti
Ogni push su `main` compila l'APK con GitHub Actions (`.github/workflows/build.yml`) e lo pubblica nelle **Releases** (versione = numero dell'esecuzione, tag `v1.N`).

L'app controlla all'avvio l'ultima Release e, se c'è una versione più nuova, mostra «Nuova versione disponibile». L'APK si scarica, poi Android chiede conferma e lo installa sopra quello esistente.
Perché funzioni **tutte le build devono essere firmate con la stessa chiave**: la chiave sta nel segreto GitHub `KEYSTORE_BASE64` (Settings › Secrets and variables › Actions). Senza il segreto la build parte comunque ma non pubblica la Release. Chi copia il progetto (fork) usa la propria chiave e ottiene un'app separata.

Le Release `model-v1`, `sam-v1`, `sam2-v1`, `migan-v1`, `sd-v1` contengono i modelli che l'app scarica: **non vanno cancellate**.

Gli script per riconvertire Stable Diffusion in ONNX sono in [`tools/sd`](tools/sd).

## Aggiornare i modelli
L'app legge `models.json` (nella radice del repository) all'avvio e dalle impostazioni: se un modello ha una `version` più alta di quella installata propone «Aggiorna modelli» (verifica SHA-256, ignora aggiornamenti con `minApp` superiore alla versione dell'app).
Per pubblicare un nuovo modello: carica il file in una nuova Release (es. `model-v2`), poi in `models.json` aumenta `version`, aggiorna `urls`, `size`, `sha256` e (facoltativo) `notes`. Se il nuovo modello richiede modifiche al codice, imposta `minApp` sul numero di versione dell'app che lo supporta.

## Licenze
Il **codice dell'app** è rilasciato con licenza **[MIT](LICENSE)**.

I modelli e le librerie di terze parti hanno **licenze proprie**, che restano valide:

| Componente | Licenza | Origine |
|---|---|---|
| LaMa (versione ONNX) | Apache-2.0 | [advimman/lama](https://github.com/advimman/lama), [Carve/LaMa-ONNX](https://huggingface.co/Carve/LaMa-ONNX) |
| MI-GAN | MIT | [Picsart-AI-Research/MI-GAN](https://github.com/Picsart-AI-Research/MI-GAN), [andraniksargsyan/migan](https://huggingface.co/andraniksargsyan/migan) |
| MobileSAM | Apache-2.0 | [ChaoningZhang/MobileSAM](https://github.com/ChaoningZhang/MobileSAM), [Acly/MobileSAM](https://huggingface.co/Acly/MobileSAM) |
| SAM 2 (Hiera Tiny) | Apache-2.0 | [facebookresearch/sam2](https://github.com/facebookresearch/sam2), [vietanhdev/segment-anything-2-onnx-models](https://huggingface.co/vietanhdev/segment-anything-2-onnx-models) |
| Stable Diffusion 1.5 Inpainting | CreativeML OpenRAIL-M | [stable-diffusion-v1-5/stable-diffusion-inpainting](https://huggingface.co/stable-diffusion-v1-5/stable-diffusion-inpainting) |
| LCM-LoRA (versione veloce) | OpenRAIL++ | [latent-consistency/lcm-lora-sdv1-5](https://huggingface.co/latent-consistency/lcm-lora-sdv1-5) |
| ONNX Runtime | MIT | [microsoft/onnxruntime](https://github.com/microsoft/onnxruntime) |
| Jetpack Compose / AndroidX | Apache-2.0 | [developer.android.com](https://developer.android.com/jetpack) |

⚠️ **Stable Diffusion** (CreativeML OpenRAIL-M / OpenRAIL++) ha **restrizioni d'uso**: non è consentito usarlo per generare contenuti illeciti, dannosi, ingannevoli o lesivi di persone. Usando i modelli generativi accetti di rispettare quelle condizioni, che trovi nelle pagine linkate sopra.

I modelli non sono inclusi in questo repository né nell'APK: sono scaricati dall'app dalle Release di questo progetto o dalle pagine originali.
