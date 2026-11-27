import sys, numpy as np
sr=44100
x=np.fromfile(sys.argv[1],dtype=np.float32).astype(np.float64)
print("durata s",len(x)/sr)
# RMS per finestre da 50 ms
w=int(0.05*sr); n=len(x)//w
r=np.sqrt(np.mean(x[:n*w].reshape(n,w)**2,axis=1)+1e-20); db=20*np.log10(r)
print("RMS 50ms percentili dB (5,10,25,50,75,90,95,99):",np.round(np.percentile(db,[5,10,25,50,75,90,95,99]),1))
# timeline 0.5 s
w2=int(0.5*sr); n2=len(x)//w2
r2=20*np.log10(np.sqrt(np.mean(x[:n2*w2].reshape(n2,w2)**2,axis=1))+1e-10)
print("timeline 0.5s:"," ".join(f"{v:.0f}" for v in r2))
# spettro medio (Welch) su finestre attive vs silenziose
N=4096; hop=1024; frames=[]
win=np.hanning(N)
for i in range(0,len(x)-N,hop):
    f=x[i:i+N]; frames.append((np.sqrt(np.mean(f**2)), np.abs(np.fft.rfft(f*win))**2))
lv=np.array([a for a,_ in frames]); P=np.array([b for _,b in frames])
thr_hi=np.percentile(lv,70); thr_lo=np.percentile(lv,15)
act=P[lv>=thr_hi].mean(0); quiet=P[lv<=thr_lo].mean(0)
fr=np.fft.rfftfreq(N,1/sr)
bands=[20,60,100,150,250,400,630,1000,1600,2500,4000,6300,10000,16000,22050]
ref=None
print("banda Hz       attivo dB  quieto dB  SNR")
for a,b in zip(bands[:-1],bands[1:]):
    m=(fr>=a)&(fr<b)
    A=10*np.log10(act[m].sum()+1e-20); Q=10*np.log10(quiet[m].sum()+1e-20)
    print(f"{a:5d}-{b:5d}   {A:8.1f}  {Q:8.1f}  {A-Q:5.1f}")
