# Privacy policy

*Last updated: 26 November 2026* · [Italiano più sotto](#informativa-sulla-privacy)

Appunti is developed by Daniele Baschieri as free and open-source software.
This policy covers the Appunti Android app (`org.appunti.app`).

## Summary

Appunti records and transcribes lectures **on your phone**. Recordings and
transcripts never leave the device unless you choose to share them or turn on
the optional AI features. There are no accounts, ads, analytics or trackers.

## What the app accesses

- **Microphone.** Used only while you are recording a lecture. A notification
  is shown for the whole recording.
- **Audio files you import.** Only the file you pick or share with the app.
- **Notifications.** To show recording and transcription progress.

## Where your data is stored

Everything stays on your phone, in two places:

- the app's own folder, which other apps cannot read and which is excluded
  from Android cloud backups (deleted when you uninstall the app);
- a copy of each lecture's audio, transcript and AI documents in
  `Documents/Appunti/` on the phone's storage, so they survive uninstalling
  the app. Like any file there, it is visible to file managers, to apps you
  have allowed to access your files and to any backup or sync service you use.

You can delete any lecture from the app or from `Documents/Appunti/`.

## Network connections

- **Speech model download.** On first launch the app downloads the speech
  recognition model (about 650 MB) from the developer's file server. Like any
  web server, it sees your IP address and the files requested. No personal data
  or recordings are sent.
- **Optional AI features (off by default).** If you configure an AI server in
  Settings (endpoint, API key, model), the transcript of the lecture you choose
  is sent to **that server** when you ask for a summary or a cleaned-up text.
  The developer does not operate or see that server; its own privacy policy
  applies. The API key is stored only on your phone.
- **Sharing and export.** When you share or export a transcript or recording,
  it goes to the app you pick, under that app's policy.

The developer does not collect, receive, sell or share any personal data.

## Children

Appunti is not directed at children under 13.

## Changes and contact

Changes to this policy are published at this address and in the app's source
repository. For questions, open an issue at
<https://github.com/DangerBlack/appunti/issues>.

---

# Informativa sulla privacy

*Ultimo aggiornamento: 26 novembre 2026*

Appunti è sviluppata da Daniele Baschieri come software libero e open source.
Questa informativa riguarda l'app Android Appunti (`org.appunti.app`).

## In breve

Appunti registra e trascrive le lezioni **sul telefono**. Registrazioni e
trascrizioni non escono mai dal dispositivo, a meno che tu non decida di
condividerle o di attivare le funzioni AI facoltative. Niente account,
pubblicità, statistiche o tracciamento.

## A cosa accede l'app

- **Microfono.** Solo mentre registri una lezione. Per tutta la registrazione
  viene mostrata una notifica.
- **File audio importati.** Solo il file che scegli o condividi con l'app.
- **Notifiche.** Per mostrare l'avanzamento di registrazione e trascrizione.

## Dove sono salvati i dati

Tutto resta sul telefono, in due posti:

- la cartella dell'app, che le altre app non possono leggere ed esclusa dai
  backup cloud di Android (si cancella disinstallando l'app);
- una copia dell'audio, della trascrizione e dei documenti AI di ogni lezione
  in `Documenti/Appunti/` nella memoria del telefono, così sopravvivono alla
  disinstallazione. Come ogni file lì, è visibile ai file manager, alle app a
  cui hai dato accesso ai file e agli eventuali servizi di backup o
  sincronizzazione che usi.

Puoi eliminare ogni lezione dall'app o da `Documenti/Appunti/`.

## Connessioni di rete

- **Download del modello vocale.** Al primo avvio l'app scarica il modello di
  riconoscimento vocale (circa 650 MB) dal server di file dello sviluppatore.
  Come ogni server web, vede il tuo indirizzo IP e i file richiesti. Non
  vengono inviati dati personali né registrazioni.
- **Funzioni AI facoltative (disattivate di default).** Se configuri un server
  AI nelle Impostazioni (indirizzo, chiave API, modello), la trascrizione della
  lezione scelta viene inviata **a quel server** quando chiedi un riassunto o
  un testo sistemato. Lo sviluppatore non gestisce né vede quel server: vale
  la sua informativa. La chiave API resta solo sul telefono.
- **Condivisione ed esportazione.** Quando condividi o esporti una
  trascrizione o una registrazione, va all'app che scegli, secondo le sue
  regole.

Lo sviluppatore non raccoglie, riceve, vende né condivide dati personali.

## Minori

Appunti non è rivolta a bambini sotto i 13 anni.

## Modifiche e contatti

Le modifiche a questa informativa sono pubblicate a questo indirizzo e nel
repository del codice sorgente. Per domande apri una segnalazione su
<https://github.com/DangerBlack/appunti/issues>.
