"""ClipForge Cloud MVP: CPU-only, upload one file -> one long and three shorts.
Run in Google Colab: !apt-get -qq install ffmpeg
!pip -q install gradio
!python cloud/app.py
"""
import json, math, os, random, shutil, subprocess, tempfile
from pathlib import Path
import gradio as gr

def run(*args):
    p=subprocess.run(list(map(str,args)),capture_output=True,text=True)
    if p.returncode: raise RuntimeError(p.stderr[-2000:])
    return p.stdout

def probe(path):
    data=json.loads(run("ffprobe","-v","error","-show_format","-show_streams","-of","json",path))
    return float(data["format"]["duration"])

def scenes(path,duration):
    # Scene changes are hints only; preserve complete 12-50s events.
    p=subprocess.run(["ffmpeg","-hide_banner","-i",str(path),"-vf","scale=320:-1,select=gt(scene\\,0.35),showinfo","-an","-f","null","-"],capture_output=True,text=True)
    import re
    stamps=[float(x) for x in re.findall(r"pts_time:([0-9.]+)",p.stderr)]
    return sorted(set([0.,duration]+[t for t in stamps if 0<t<duration]))

def candidates(path,duration):
    marks=scenes(path,duration)
    windows=[]
    # Cut on scene boundaries when possible; never export incomplete 1-3s fragments.
    for i,start in enumerate(marks[:-1]):
        for length in (18,28,40):
            desired=min(duration,start+length)
            end=min(marks,key=lambda t:abs(t-desired)) if marks else desired
            if end-start<12 or end-start>55: end=desired
            if 12<=end-start<=55: windows.append((start,end))
    if not windows:
        windows=[(s,min(duration,s+25)) for s in range(0,int(duration),25) if min(duration,s+25)-s>=8]
    # Audio energy scoring: ffmpeg volumedetect across sample; no remote APIs.
    ranked=[]
    for start,end in windows[:150]:
        try:
            p=subprocess.run(["ffmpeg","-hide_banner","-ss",str(start),"-t",str(min(6,end-start)),"-i",str(path),"-vn","-af","volumedetect","-f","null","-"],capture_output=True,text=True,timeout=30)
            import re
            m=re.search(r"mean_volume:\s*(-?[0-9.]+) dB",p.stderr)
            score=float(m.group(1)) if m else -60.
        except Exception: score=0.
        ranked.append((score,start,end))
    return ranked

def render(path,shots,out,vertical=False):
    if not shots: raise RuntimeError("No usable moments found")
    parts=[]
    for i,(_,a,b) in enumerate(shots):
        part=out.parent/f"part_{out.stem}_{i:03d}.mp4"
        vf=("scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920" if vertical
            else "scale=1280:720:force_original_aspect_ratio=decrease,pad=1280:720:(ow-iw)/2:(oh-ih)/2")
        run("ffmpeg","-y","-ss",f"{a:.3f}","-i",path,"-t",f"{b-a:.3f}","-vf",vf,
            "-c:v","libx264","-preset","veryfast","-crf","25","-r","30","-c:a","aac",
            "-ar","48000","-ac","2","-movflags","+faststart",part)
        parts.append(part)
    manifest=out.parent/f"concat_{out.stem}.txt"
    manifest.write_text("".join("file '"+str(p.resolve()).replace("'","'\\''")+"'\n" for p in parts))
    run("ffmpeg","-y","-f","concat","-safe","0","-i",manifest,"-c","copy","-movflags","+faststart",out)
    for p in parts:p.unlink(missing_ok=True)
    manifest.unlink(missing_ok=True)
    if probe(out)<sum(b-a for _,a,b in shots)*0.90: raise RuntimeError("Output duration validation failed")

def produce(upload,progress=gr.Progress()):
    if not upload: raise gr.Error("Upload a source video")
    folder=Path(tempfile.mkdtemp(prefix="clipforge_cloud_"))
    src=folder/("source"+Path(upload).suffix)
    shutil.copy2(upload,src)
    duration=probe(src)
    if duration<40: raise gr.Error("Source should be at least 40 seconds")
    progress(.1,desc="Scanning entire video")
    pool=candidates(src,duration)
    # Keep original chronology WITHIN events. Shuffle only complete events.
    pool.sort(key=lambda item:item[0],reverse=True)
    selected=[]; used=[]
    target=min(duration*.68,900)
    for item in pool:
        _,a,b=item
        if all(b<=x or a>=y for x,y in used):
            selected.append(item);used.append((a,b))
        if sum(e-s for _,s,e in selected)>=target:break
    if not selected:raise gr.Error("Could not select complete moments")
    progress(.25,desc="Rendering long video")
    long=folder/"ClipForge_Long.mp4";render(src,selected,long)
    shorts=[]
    for i in range(3):
        # Different nonoverlapping event groups, no truncated endings.
        shots=[s for s in selected if s[2]-s[1]<=60][i:i+1]
        if not shots: raise gr.Error("Not enough distinct complete moments for three Shorts")
        out=folder/f"ClipForge_Short_{i+1}.mp4"
        progress(.40+i*.18,desc=f"Rendering short {i+1}/3")
        render(src,shots,out,vertical=True);shorts.append(str(out))
    progress(1.,desc="Complete")
    return str(long),*shorts,"Done: 1 long + 3 shorts. Scene scoring is heuristic, not semantic story comprehension."

with gr.Blocks(title="ClipForge Cloud MVP") as app:
    gr.Markdown("# ClipForge Cloud — One upload, four exports\nCPU-based prototype. No paid API. Keep the Colab session open while processing.")
    source=gr.Video(label="Upload source")
    go=gr.Button("Auto Analyze & Generate",variant="primary")
    long=gr.File(label="Long video")
    shorts=[gr.File(label=f"Short {i}") for i in (1,2,3)]
    status=gr.Textbox(label="Status")
    go.click(produce,inputs=[source],outputs=[long,*shorts,status])
app.queue(max_size=3).launch(share=True)
