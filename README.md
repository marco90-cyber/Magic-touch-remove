# Magic Touch Remove

App Android (Kotlin + Jetpack Compose) per rimuovere oggetti dalle foto con l'IA, in stile TouchRetouch.

## Funzioni
- **Pennello**: dipingi l'oggetto da eliminare (dimensione, durezza, opacità regolabili)
- **Lasso**: disegna un contorno libero attorno all'oggetto
- **Gomma**: cancella parte della selezione
- **Ripristina**: rimette i pixel originali dove l'IA ha toccato troppo
- **Cancella AI** (o modalità **AUTO** che parte appena alzi il dito)
- Lente d'ingrandimento mentre disegni, zoom e spostamento a due dita
- Annulla / Ripeti, salva in galleria o condividi

## IA
[LaMa – Resolution-robust Large Mask Inpainting](https://github.com/advimman/lama) (Apache-2.0), nella versione ONNX
di [Carve/LaMa-ONNX](https://huggingface.co/Carve/LaMa-ONNX), eseguito sul telefono con ONNX Runtime.
Per la massima precisione l'app ritaglia la zona attorno alla maschera, la ricostruisce a 512x512 e reincolla il risultato
sulla foto originale sostituendo solo i pixel selezionati. Il modello (~200 MB) viene scaricato al primo avvio.

## Build
Ogni push su `main` compila l'APK con GitHub Actions e lo pubblica nelle **Releases**.

## Aggiornare i modelli
L'app legge `models.json` (nella radice del repository) all'avvio e dalle *Informazioni*: se un modello ha una `version` più alta di quella installata
propone «Aggiorna modelli» (verifica SHA-256, ignora aggiornamenti con `minApp` superiore alla versione dell'app).
Per pubblicare un nuovo modello: carica il file in una nuova Release (es. `model-v2`), poi in `models.json` aumenta `version`,
aggiorna `urls`, `size`, `sha256` e (facoltativo) `notes`. Se il nuovo modello richiede modifiche al codice, imposta `minApp`
sul numero di versione dell'app che lo supporta.

## Aggiornamenti dell'app e firma
L'app controlla all'avvio l'ultima Release di GitHub e, se c'è una versione più nuova, mostra «Nuova versione disponibile» con **Aggiorna** / **Più tardi**.
L'APK si scarica, poi Android chiede conferma e lo installa sopra quello esistente (modelli e impostazioni restano).

Perché funzioni **tutte le build devono essere firmate con la stessa chiave**: la chiave sta nel segreto GitHub `KEYSTORE_BASE64`
(Settings → Secrets and variables → Actions). Senza il segreto la build parte comunque ma **non pubblica la Release**.
Chi copia il progetto (fork) usa la propria chiave e ottiene un'app separata.
