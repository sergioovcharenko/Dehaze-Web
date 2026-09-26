"""Package source plus the exact tested offline model; not an APK decompilation."""
import sys
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[2]
with zipfile.ZipFile(sys.argv[1], 'w', zipfile.ZIP_DEFLATED) as out:
    for path in sorted((root / 'android-lab3/engine').rglob('*')):
        if path.is_file() and 'build' not in path.parts:
            out.write(path, str(path.relative_to(root / 'android-lab3')))
    for name in ('ANTIFOG-INTEGRATION.md', 'LAB4-QUICKSTART.md'):
        out.write(root / 'docs' / name, name)
    for path in (root / 'android-lab3/app/src/main/assets').glob('*LICENSE*'):
        out.write(path, 'licenses/' + path.name)
