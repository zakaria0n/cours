# Publier une mise à jour

La publication construit un APK signé, crée une Release GitHub et rend la mise à jour
visible dans l'application.

## Depuis GitHub

1. Ouvrir **Actions**.
2. Choisir **Build and publish Android TV APK**.
3. Cliquer **Run workflow** et saisir la nouvelle version, par exemple `1.2.0`.

## Depuis Git

```bash
git tag v1.2.0
git push origin v1.2.0
```

Ne jamais supprimer les secrets de signature ni perdre la clé locale. Sans la même
clé, Android refusera d'installer une mise à jour sur une version existante.
