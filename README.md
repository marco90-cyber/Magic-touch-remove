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
