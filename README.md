# UtensiliPlugin

Plugin Paper per Minecraft 1.20.4 (Java 17).

## Compilazione

```bash
mvn package
```

Copia `target/utensiliplugin-1.0.0.jar` nella cartella `plugins` del server Paper.

## Uso

- `/utensili` apre la GUI; è disponibile agli operatori e a chi ha il permesso `utensili.staff`.
- Cliccando un utensile nella GUI lo si riceve (se l'inventario è pieno viene lasciato a terra).
- Ascia VDF: ascia di ferro con CustomModelData 1. Rompe le porte di legno e le ripristina dopo 3 minuti, solo se lo spazio è ancora libero.
- Ariete: bastone con CustomModelData 66. Tasto destro su porte di legno o di ferro per sfondarle in 8 secondi.
- Piede di porco: bastone con CustomModelData 65. Tasto destro su porte di ferro o sbarre di ferro per sfondarle in 8 secondi.
- Per Ariete e piede di porco viene mostrata una barra boss verde che avanza durante gli 8 secondi; l'operazione si interrompe se il giocatore cambia utensile o si allontana.

Per consentire anche a staff non-op di aprire la GUI, assegna `utensili.staff` tramite il plugin permessi del server.
