"""Run Xvfb and Qt inside the same execution/network namespace."""
import argparse,os,secrets,socket,struct,subprocess,tempfile,time,sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from prepare_assets import generate_worker
generate_worker()
p=argparse.ArgumentParser();p.add_argument('--tools',required=True);p.add_argument('test',nargs='?',default='test_qml.py');a=p.parse_args()
tools=Path(a.tools).resolve();display=100+secrets.randbelow(400)
with tempfile.TemporaryDirectory(prefix='qt-display-',dir=tools) as d:
    auth=Path(d)/'auth';fields=[b'',str(display).encode(),b'MIT-MAGIC-COOKIE-1',secrets.token_bytes(16)]
    auth.write_bytes(struct.pack('>H',65535)+b''.join(struct.pack('>H',len(x))+x for x in fields));auth.chmod(0o600)
    env=os.environ.copy();env.update(LD_LIBRARY_PATH=str(tools/'xserver/usr/lib/x86_64-linux-gnu'),DISPLAY='127.0.0.1:'+str(display),XAUTHORITY=str(auth),PYTHONPATH=str(tools))
    log=(Path(d)/'xserver.log').open('w')
    server=subprocess.Popen([str(tools/'xserver/usr/bin/Xvfb'),':'+str(display),'-screen','0','1280x800x24','-listen','tcp','-nolisten','unix','-auth',str(auth)],env=env,stdout=log,stderr=log)
    try:
        for _ in range(100):
            try:s=socket.create_connection(('127.0.0.1',6000+display),.1);s.close();break
            except OSError:
                if server.poll() is not None:raise RuntimeError((Path(d)/'xserver.log').read_text())
                time.sleep(.1)
        else:raise RuntimeError('Display not listening')
        result=subprocess.run(['python3',str(Path(__file__).with_name(a.test))],env=env)
    finally:server.terminate();server.wait(timeout=5);log.close()
    raise SystemExit(result.returncode)
