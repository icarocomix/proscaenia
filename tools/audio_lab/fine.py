import sys, numpy as np
sr=44100; x=np.fromfile(sys.argv[1],dtype=np.float32).astype(np.float64)
N=32768; win=np.hanning(N); P=0; k=0
for i in range(0,len(x)-N,N//4): P=P+np.abs(np.fft.rfft(x[i:i+N]*win))**2; k+=1
P/=k; fr=np.fft.rfftfreq(N,1/sr); db=10*np.log10(P+1e-20)
m=(fr>30)&(fr<400); idx=np.argsort(db[m])[::-1][:12]
print("picchi 30-400 Hz:",[(round(fr[m][i],1),round(db[m][i],1)) for i in sorted(idx)])
# densità per Hz in terzi d'ottava, normalizzata a 1 kHz
cent=[125,160,200,250,315,400,500,630,800,1000,1250,1600,2000,2500,3150,4000,5000,6300,8000,10000]
ref=None; out=[]
for c in cent:
    m=(fr>=c/2**(1/6))&(fr<c*2**(1/6)); d=10*np.log10(P[m].mean())
    out.append(d)
r=out[cent.index(1000)]
print("1/3 ott. densità rel. 1 kHz:"," ".join(f"{c}:{d-r:+.0f}" for c,d in zip(cent,out)))
