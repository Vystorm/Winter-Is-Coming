#!/usr/bin/env python3
"""Data visualization of server-exported blocks, never a Minecraft client screenshot.
Requires Pillow. Input JSON must originate from WinterVisual's real server run.
No snow/ice is inferred or added here; materials and snow heights come from snapshots.
"""
import argparse, json, re, hashlib
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

FONT='/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
BOLD='/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'
BG='#101d29'; FG='#eef5f9'; MUTED='#9db2c2'; ACCENT='#71d9de'
COLORS={'GRASS_BLOCK':'#6c9660','DIRT':'#775741','STONE':'#7c8793','SAND':'#cfbc8b','WATER':'#246b86','ICE':'#9bd8e8','SNOW':'#f5faff','SNOW_BLOCK':'#ecf4fa','OAK_PLANKS':'#a88253','SPRUCE_PLANKS':'#785a42','OAK_LOG':'#785535','SPRUCE_LOG':'#604c37','OAK_LEAVES':'#3c6d49','SPRUCE_LEAVES':'#365c46','GLASS':'#a1c5d0','DIRT_PATH':'#a28d63','COBBLESTONE':'#89919a','BRICKS':'#986858','STONE_BRICKS':'#929b9e','GOLD_BLOCK':'#e6c961'}
LABELS=[('VOR DEM WINTER','01. November 2026','Freies Wasser, unveränderte Küste.'),('ERSTER SCHNEE','30. November 2026','Die Saison beginnt, Eis wächst am Ufer.'),('WEIHNACHTEN','24. Dezember 2026','Maximale Winterstärke im Test.'),('TAUWETTER','15. Februar 2027','Schnee und Küsteneis gehen zurück.'),('NACH DEM WINTER','02. März 2027','Rückkehr zum gespeicherten Ausgangszustand.')]
def font(n,b=False): return ImageFont.truetype(BOLD if b else FONT,n)
def rgb(c): return tuple(bytes.fromhex(c.lstrip('#')))
def shade(c,f): return tuple(min(255,int(v*f)) for v in rgb(c))
def mat(b): return b['material'].upper().split(':')[-1]
def height(b):
    m=mat(b)
    if m=='SNOW':
        match=re.search(r'layers=(\d+)',b['data']); return int(match.group(1))/8 if match else 1/8
    if m=='DIRT_PATH': return 15/16
    return 1

def scene(snapshot, size=(1180,850)):
    im=Image.new('RGB',size,BG); d=ImageDraw.Draw(im)
    blocks=snapshot['blocks']; bypos={(b['x'],b['y'],b['z']):b for b in blocks}
    # Orthographic projection; fixed camera, scale and origin across every stage.
    scale=17; vertical=22; ox=size[0]/2; oy=180
    def p(x,y,z): return (ox+(x-z)*scale, oy+(x+z)*scale*.49-(y-70)*vertical)
    faces=[]
    for b in blocks:
        x,y,z=b['x'],b['y'],b['z']; h=height(b); m=mat(b); c=COLORS.get(m,'#b79b7c')
        above=bypos.get((x,y+1,z)); nx=bypos.get((x+1,y,z)); nz=bypos.get((x,y,z+1))
        def face(points,color):
            depth=sum(a[0]+a[2]+a[1]*.757 for a in points)/len(points)
            faces.append((depth, [p(*q) for q in points],color))
        if not above or h<1:
            face([(x,y+h,z),(x+1,y+h,z),(x+1,y+h,z+1),(x,y+h,z+1)],rgb(c))
        for neighbor,which in [(nx,'x'),(nz,'z')]:
            low=height(neighbor) if neighbor else 0
            if low>=h: continue
            if which=='x': points=[(x+1,y+low,z),(x+1,y+low,z+1),(x+1,y+h,z+1),(x+1,y+h,z)]
            else: points=[(x,y+low,z+1),(x+1,y+low,z+1),(x+1,y+h,z+1),(x,y+h,z+1)]
            face(points,shade(c,.67 if which=='x' else .81))
    for _,points,color in sorted(faces,key=lambda f:f[0]):
        d.polygon(points,fill=color)
        d.line(points+[points[0]],fill=tuple(max(0,v-11) for v in color),width=1)
    # Annotation identifies the Core-protected test plot, without changing block data.
    plot=[p(25,71.05,3),p(29,71.05,3),p(29,71.05,7),p(25,71.05,7)]
    d.line(plot+[plot[0]],fill='#ffce79',width=3)
    q=p(29,71.05,3);d.text((q[0]+12,q[1]-24),'Core-Schutz',font=font(16,True),fill='#ffce79')
    return im

def counts(s):
    snow=[b for b in s['blocks'] if mat(b)=='SNOW']; ice=[b for b in s['blocks'] if mat(b)=='ICE']
    return len(snow),len(ice),sum(round(height(b)*8) for b in snow)

def main():
    ap=argparse.ArgumentParser();ap.add_argument('snapshots',type=Path);ap.add_argument('output',type=Path);ap.add_argument('--version',required=True);ap.add_argument('--baseline',type=Path);args=ap.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    paths=sorted(args.snapshots.glob('*.json'))
    paths=[p for p in paths if p.name!='result.json']; panels=[]; manifest=[]
    assert len(paths)==5, f'Expected five actual snapshots, got {len(paths)}'
    snapshots=[json.loads(p.read_text()) for p in paths]
    for index,(path,s) in enumerate(zip(paths,snapshots)):
        title,date,note=LABELS[index]; im=Image.new('RGB',(1600,1150),BG);d=ImageDraw.Draw(im)
        d.text((54,35),'VYSTORM  /  WINTER IS COMING '+args.version,font=font(22,True),fill=ACCENT)
        d.text((54,85),title,font=font(43,True),fill=FG)
        d.text((54,144),date+'  ·  gleiche Testwelt, gleiche Perspektive',font=font(22),fill=MUTED)
        im.paste(scene(s),(0,200));d=ImageDraw.Draw(im)
        snow,ice,layers=counts(s)
        x=1190; d.text((x,242),'GEMESSENER ZUSTAND',font=font(18,True),fill=ACCENT)
        for row,(value,label) in enumerate([(snow,'Schneepositionen'),(ice,'Eisblöcke'),(layers,'Schneeschichten gesamt')]):
            yy=305+row*135;d.text((x,yy),str(value),font=font(50,True),fill=FG);d.text((x,yy+63),label,font=font(19),fill=MUTED)
        d.text((x,758),'FARBEN',font=font(18,True),fill=ACCENT)
        for row,(m,label) in enumerate([('SNOW','Schnee'),('ICE','Eis'),('WATER','Offenes Wasser'),('GRASS_BLOCK','Land / Schutzfläche')]):
            yy=800+row*36;d.rectangle((x,yy,x+22,yy+22),fill=COLORS[m]);d.text((x+34,yy-1),label,font=font(18),fill=FG)
        d.line((54,1015,1546,1015),fill='#334653',width=2)
        d.text((54,1036),note,font=font(24,True),fill=FG)
        d.text((54,1081),'Echte Server-Blockdaten · schematischer Render, kein Client-Screenshot · Test: Ozeanradius 8 Blöcke',font=font(19),fill=MUTED)
        out=args.output/(path.stem+'.png');im.save(out);panels.append(im)
        manifest.append({'snapshot':path.name,'snapshotSha256':hashlib.sha256(path.read_bytes()).hexdigest(),'image':out.name,'snowPositions':snow,'iceBlocks':ice,'snowLayers':layers})
    overview=Image.new('RGB',(2400,1820),BG);d=ImageDraw.Draw(overview)
    d.text((50,35),'WINTER IS COMING',font=font(48,True),fill=FG)
    d.text((50,100),'VYSTORM  /  Vorher → Winter → Tauwetter → Nachher',font=font(28),fill=ACCENT)
    for i,panel in enumerate(panels): overview.paste(panel.resize((800,575),Image.Resampling.LANCZOS),((i%3)*800,175+(i//3)*655))
    d=ImageDraw.Draw(overview);x,y=1650,900
    for n,line in enumerate(['DATEN STATT KONZEPTBILD','5 Phasen derselben Serverwelt','Schnee/Eis vom Plugin erzeugt','Feste Kamera und Farblegende','Radius 8 statt Standard 40','Beschleunigter Testablauf','Keine Client-Wetterdarstellung']):
        d.text((x,y+n*57),line,font=font(24,n==0),fill=ACCENT if n==0 else FG)
    d.text((50,1640),'Schematische Blockdaten-Ansichten aus einem echten Purpur-Serverlauf.',font=font(30,True),fill=FG)
    d.text((50,1700),'Kein Minecraft-Client-Screenshot. Texturen, Niederschlag und Partikeleffekte sind hier nicht visuell geprüft.',font=font(25),fill=MUTED)
    overview.save(args.output/'00-vergleich.png')
    topview=Image.new('RGB',(1800,920),BG);d=ImageDraw.Draw(topview)
    d.text((48,38),'KÜSTENFROST & SCHNEE  /  DRAUFSICHT',font=font(38,True),fill=FG)
    d.text((48,103),'Jedes Feld = ein Block. Obere sichtbare Oberfläche aus dem echten Serverzustand.',font=font(22),fill=MUTED)
    for idx,s in enumerate(snapshots):
        x0=48+idx*347;y0=240;step=10;tops={}
        for b in s['blocks']:
            pos=(b['x'],b['z'])
            if pos not in tops or b['y']>tops[pos]['y']:tops[pos]=b
        d.text((x0,179),LABELS[idx][0],font=font(18,True),fill=ACCENT)
        d.text((x0,207),LABELS[idx][1],font=font(17),fill=MUTED)
        for (x,z),b in tops.items():
            c=COLORS.get(mat(b),'#b79b7c');d.rectangle((x0+x*step,y0+z*step,x0+(x+1)*step-1,y0+(z+1)*step-1),fill=c)
        d.rectangle((x0+25*step,y0+3*step,x0+29*step,y0+7*step),outline='#ffce79',width=3)
        snow,ice,layers=counts(s)
        d.text((x0,588),f'{snow} Schnee / {ice} Eis',font=font(20,True),fill=FG)
        d.text((x0,622),f'{layers} Schneeschichten',font=font(18),fill=MUTED)
    d.text((48,715),'Gelber Rahmen: geschützte Core-Region. Sie bleibt in allen Phasen schneefrei.',font=font(24),fill='#ffce79')
    d.text((48,765),'Helles Blau: Eis · dunkles Blau: offenes Wasser · Weiß: Schnee · Grün: Land',font=font(23),fill=FG)
    d.text((48,840),'Schematischer Datenrender, kein Client-Screenshot. Ozeanradius für diesen Test: 8 Blöcke.',font=font(21),fill=MUTED)
    topview.save(args.output/'06-kuestenvergleich.png')
    (args.output/'render-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    if args.baseline:
        old=json.loads((args.baseline/'03-peak.json').read_text())
        comparison=Image.new('RGB',(2400,1220),BG);d=ImageDraw.Draw(comparison)
        d.text((50,38),'UFERFROST  /  FEHLER GEFUNDEN UND KORRIGIERT',font=font(41,True),fill=FG)
        d.text((50,102),'Identische Testlandschaft · 24. Dezember · Ozeanbudget 8 Blöcke',font=font(26),fill=MUTED)
        d.text((50,175),'1.0.0  /  LÜCKEN AM UFER',font=font(30,True),fill='#ffb59e')
        d.text((1250,175),'1.0.1  /  DURCHGEHENDER KÜSTENFROST',font=font(30,True),fill=ACCENT)
        comparison.paste(scene(old),(0,230));comparison.paste(scene(snapshots[2]),(1200,230));d=ImageDraw.Draw(comparison)
        d.text((50,1058),f'{counts(old)[1]} Eisblöcke · nahe Uferbereiche übersprungen',font=font(27,True),fill='#ffb59e')
        d.text((1250,1058),f'{counts(snapshots[2])[1]} Eisblöcke · Ufer lückenlos im Mindestbudget',font=font(27,True),fill=ACCENT)
        d.text((50,1152),'Echte Server-Blockdaten aus zwei isolierten Läufen. Schematische Render, keine Minecraft-Client-Screenshots.',font=font(24),fill=MUTED)
        comparison.save(args.output/'07-fehler-korrigiert.png')
    print(json.dumps(manifest,indent=2))
if __name__=='__main__':main()
