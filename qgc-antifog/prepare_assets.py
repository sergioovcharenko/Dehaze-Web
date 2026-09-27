"""Qt 5 WorkerScript does not provide QML .import namespaces."""
from pathlib import Path
def generate_worker():
    qml=Path(__file__).parent/'qml'
    core=(qml/'ClassicCore.js').read_text().removeprefix('.pragma library\n')
    handler=(qml/'ClassicWorker.js').read_text()
    output=qml/'ClassicWorker.bundle.js'
    output.write_text('// Generated from ClassicCore.js and ClassicWorker.js.\n'+core+'\n'+handler)
    return output
if __name__=='__main__':generate_worker()
