#!/usr/bin/env python3
"""Build/sign the genuine Android companion; private signing files remain under tools/."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from privacy_guard import publishable

ROOT = Path(__file__).resolve().parent
APP = ROOT / "companion"
SDK = ROOT / "tools/android-sdk"
BUILD_TOOLS = SDK / "build-tools/36.0.0"
DEFAULT_JAVA = Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else Path("C:/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot")

def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()

def sources():
    paths = [p for p in APP.rglob("*") if p.is_file() and publishable(p.relative_to(ROOT).as_posix())]
    paths += [Path(__file__).resolve(), ROOT / "gradle/wrapper/gradle-wrapper.jar", ROOT / "gradle/wrapper/gradle-wrapper.properties"]
    return [{"path":p.relative_to(ROOT).as_posix(),"sha256":digest(p),"bytes":p.stat().st_size} for p in sorted(set(paths))]

def fingerprint(records):
    return hashlib.sha256(json.dumps(sorted(records,key=lambda r:r['path']),sort_keys=True,separators=(',',':')).encode()).hexdigest()

def validate_java(java_home):
    try:
        result = subprocess.run([str(java_home/'bin/java.exe'), '-version'], capture_output=True, text=True,
                                encoding='utf-8', errors='replace', check=True)
    except (OSError, subprocess.CalledProcessError):
        raise SystemExit('A working JDK 21 is required. Pass --java-home "C:/path/to/jdk-21".') from None
    match = re.search(r'version\s+"([0-9]+)', result.stdout + result.stderr)
    if not match or int(match.group(1)) != 21:
        raise SystemExit('The selected Java must be JDK 21. Pass --java-home "C:/path/to/jdk-21"; JAVA_HOME may select a different version.')

def gradle_directory():
    # Windows Java test workers lose non-ASCII classpath entries. A junction
    # keeps the exact same sources/build files available through an ASCII path.
    if os.name != 'nt' or ROOT.as_posix().isascii(): return ROOT
    alias=Path(tempfile.gettempdir())/'haaru-native-workspace'
    if alias.exists():
        if not os.path.isjunction(alias) or alias.resolve()!=ROOT:
            raise SystemExit('Native build alias is occupied by unrelated data')
    else:
        subprocess.run(['cmd.exe','/c','mklink','/J',str(alias),str(ROOT)],check=True,capture_output=True)
    if alias.resolve()!=ROOT or not alias.as_posix().isascii():raise SystemExit('Invalid native build alias')
    return alias

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home',type=Path,default=DEFAULT_JAVA)
    args=parser.parse_args()
    validate_java(args.java_home)
    env=os.environ.copy();env['JAVA_HOME']=str(args.java_home);env['ANDROID_SDK_ROOT']=str(SDK)
    env['PATH']=str(args.java_home/'bin')+os.pathsep+env['PATH']
    for name in ['zipalign.exe','apksigner.bat','aapt2.exe']:
        if not (BUILD_TOOLS/name).exists():raise SystemExit('Missing official build tool '+name)
    gradle_root=gradle_directory()
    (APP/'local.properties').write_text('sdk.dir='+(gradle_root/'tools/android-sdk').as_posix().replace(':','\\:')+'\n',encoding='utf-8')
    before=sources()
    subprocess.run([str(args.java_home/'bin/java.exe'),'-cp','gradle/wrapper/gradle-wrapper.jar',
        'org.gradle.wrapper.GradleWrapperMain','--no-daemon','-p','companion','testDebugUnitTest','assembleRelease'],cwd=gradle_root,env=env,check=True)
    suites=[ET.parse(p).getroot() for p in (APP/'build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
    tested=sum(int(s.attrib['tests']) for s in suites)
    if tested<7 or any(int(s.attrib.get('failures',0))+int(s.attrib.get('errors',0)) for s in suites):
        raise SystemExit('Native state regression tests did not pass')
    if fingerprint(before)!=fingerprint(sources()):raise SystemExit('Companion sources changed during compilation; rebuild')
    candidates=list((APP/'build/outputs/apk/release').glob('*-release-unsigned.apk'))
    if len(candidates)!=1:raise SystemExit('Expected exactly one compiled release APK')
    unsigned=candidates[0]
    properties=dict(line.split('=',1) for line in (ROOT/'module/module.prop').read_text(encoding='utf-8').splitlines() if '=' in line)
    version=properties['version'];version_code=int(properties['versionCode'])
    signing=ROOT/'tools/companion-signing';signing.mkdir(parents=True,exist_ok=True)
    rel=lambda p:str(p.relative_to(ROOT))
    store=signing/'haaru-release.jks';password=signing/'password.txt'
    if not store.exists() and not password.exists():
        password.write_text(secrets.token_urlsafe(48),encoding='ascii')
        env['HAARU_SIGNING_PASSWORD']=password.read_text()
        subprocess.run([str(args.java_home/'bin/keytool.exe'),'-genkeypair','-keystore',rel(store),'-storetype','JKS',
            '-alias','haaru','-keyalg','RSA','-keysize','3072','-validity','10000',
            '-dname','CN=Cekxri, OU=Haaru, O=Haaru.UwU','-storepass:env','HAARU_SIGNING_PASSWORD',
            '-keypass:env','HAARU_SIGNING_PASSWORD'],cwd=ROOT,env=env,check=True)
        env.pop('HAARU_SIGNING_PASSWORD',None)
    if not store.is_file() or not password.is_file():raise SystemExit('Incomplete release signing identity; restore private signing files')
    dist=ROOT/'dist';dist.mkdir(exist_ok=True);output=dist/f'Haaru-{version}.apk'
    aligned=APP/'build/haaru-aligned.apk'
    subprocess.run([str(BUILD_TOOLS/'zipalign.exe'),'-f','-P','16','4',rel(unsigned),rel(aligned)],cwd=ROOT,env=env,check=True)
    signer=[str(args.java_home/'bin/java.exe'),'-jar',rel(BUILD_TOOLS/'lib/apksigner.jar')]
    env['HAARU_SIGNING_PASSWORD']=password.read_text(encoding='ascii').strip()
    subprocess.run(signer+['sign','--ks',rel(store),'--ks-key-alias','haaru',
        '--ks-pass','env:HAARU_SIGNING_PASSWORD','--key-pass','env:HAARU_SIGNING_PASSWORD',
        '--out',rel(output),rel(aligned)],cwd=gradle_root,env=env,check=True)
    env.pop('HAARU_SIGNING_PASSWORD',None)
    verification=subprocess.check_output(signer+['verify','--verbose','--print-certs',rel(output)],cwd=ROOT,env=env,text=True)
    certificate=re.search(r'Signer #1 certificate SHA-256 digest: ([a-f0-9]+)',verification).group(1)
    badging=subprocess.check_output([str(BUILD_TOOLS/'aapt2.exe'),'dump','badging',rel(output)],cwd=ROOT,env=env,text=True,encoding='utf-8')
    assert "name='dev.cekxri.haaru'" in badging and f"versionName='{version}'" in badging and f"versionCode='{version_code}'" in badging
    assert "launchable-activity: name='dev.cekxri.haaru.MainActivity'" in badging
    manifest={'buildMode':'built-and-staged','version':version,'versionCode':version_code,'applicationId':'dev.cekxri.haaru',
        'apkFile':output.name,'apkSha256':digest(output),'apkBytes':output.stat().st_size,'sources':before,'sourceTreeSha256':fingerprint(before),
        'signingCertificateSha256':certificate,'androidBuildTools':'36.0.0','buildToolsPath':'tools/android-sdk/build-tools/36.0.0',
        'libraries':{'kotlin':'2.4.20','androidGradlePlugin':'9.5.1','androidxCompose':'1.12.0','miuix':'0.9.4','material3':'1.4.0','backdrop':'2.0.1'},
        'compileSdk':'37.2','minSdk':29,'targetSdk':36,'hostUnitTests':tested,'hostUnitTestsPassed':True,'deviceTested':False}
    (dist/'companion-build-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
    print('Signed companion:',output.name,output.stat().st_size,'bytes; SHA256',manifest['apkSha256'])
    print('Keep tools/companion-signing private and backed up; it is required for future in-place APK updates.')

if __name__=='__main__':main()
