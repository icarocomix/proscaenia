# Metriche oggettive: profondità di modulazione dell'inviluppo (2-8 Hz) per banda d'ottava,
# rapporto parlato/pause e bilancio spettrale rispetto allo spettro medio del parlato (LTASS).
import sys, numpy as np
sys.path.insert(0, sys.argv[0].rsplit('/',1)[0]); from chain import biq_pk, lfilter
sr=44100
def bandpass(x,fc):
    # Due campane strette in cascata come passa-banda approssimato d'ottava (Q~1.4)
    import math
    w=2*math.pi*fc/sr; q=1.41; al=math.sin(w)/(2*q); c=math.cos(w); a0=1+al
    co=[al/a0,0,-al/a0,-2*c/a0,(1-al)/a0]
    return lfilter(co,lfilter(co,x))
def moddepth(x):
    out=[]
    for fc in [500,1000,2000,4000]:
        b=bandpass(x,fc); env=b*b
        # inviluppo decimato a 100 Hz
        h=sr//100; n=len(env)//h; e=env[:n*h].reshape(n,h).mean(1)
        E=np.abs(np.fft.rfft(e-e.mean()))**2; f=np.fft.rfftfreq(n,1/100)
        m=(f>=2)&(f<=8); out.append(np.sqrt(2*E[m].sum())/ (e.mean()*n) )
    return out
for p in sys.argv[1:]:
    x=np.fromfile(p,dtype=np.float32).astype(np.float64)
    w=int(.05*sr); n=len(x)//w; db=10*np.log10(np.mean(x[:n*w].reshape(n,w)**2,1)+1e-20)
    md=moddepth(x)
    print(f"{p.split('/')[-1]:18s} mod500/1k/2k/4k={' '.join(f'{v:.2f}' for v in md)}  p95-p10={np.percentile(db,95)-np.percentile(db,10):.1f} dB  p95={np.percentile(db,95):.1f} p10={np.percentile(db,10):.1f}")
