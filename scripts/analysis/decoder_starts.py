# Offline count of decoder openings, free continuations and seeks an export of a project.json would make (DECISIONS.md, decoder reuse).
# Usage: python3 -I scripts/analysis/decoder_starts.py <project.json>. Models one decoder per (asset, track) released after 2 s idle.
import json, collections
import sys
d=json.load(open(sys.argv[1]))
assets={a['id']:a for a in d['mediaLibrary']}
fps=60; IDLE=max(60,2*fps)
# slot = (assetId, layer, lane=0); layer counts visual tracks incl. title tracks (order as in tracks)
events=[]
layer=0
for t in d['tracks']:
    if t['id'].startswith('track-a'): continue
    for c in t['clips']:
        a=assets.get(c['assetId'])
        if not a or not a['hasVideo'] or a['isImage']: continue
        dur=c['sourceOutFrame']-c['sourceInFrame']
        events.append(dict(slot=(c['assetId'],layer),start=c['timelineStartFrame'],end=c['timelineStartFrame']+dur,
                           sin=c['sourceInFrame'],sout=c['sourceOutFrame'],name=a['displayName'],dur_a=a['durationFrames']))
    layer+=1
events.sort(key=lambda e:e['start'])
last={}   # slot -> (lastUsedFrame, nextSourceFrame)
opens=0; seeks_cont=0; seeks_near=0; seeks_jump=0; cont0=0
rows=[]
total_end=max(e['end'] for e in events)
for e in events:
    s=e['slot']; st=e['start']
    if s in last:
        lu,nxt=last[s]
        # released if idle check (every 30 frames) saw frame-lu>IDLE before st
        # first check frame multiple of 30 > lu+IDLE
        first=((lu+IDLE)//30+1)*30
        released = first<=st-0
    else:
        released=True
    if released:
        opens+=1; kind='OPEN'
        g=None
    else:
        g=e['sin']-nxt
        if g==0: kind='continue(no seek)'; cont0+=1
        elif 0<g<=120: kind='fwd<=120 (decode through)'; seeks_near+=1
        else: kind='SEEK g=%d'%g; seeks_jump+=1
    rows.append((st,e['name'],e['sin'],kind,g))
    last[s]=(e['end']-1,e['sout'])
print('video clips',len(events),'timeline frames',total_end, 'min %.1f'%(total_end/fps/60))
print('decoder opens',opens,'| reuse continue',cont0,'| near forward',seeks_near,'| seek jumps',seeks_jump)
for r in rows: print(r)
