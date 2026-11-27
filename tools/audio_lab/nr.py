# Riduzione adattativa del rumore: STFT (N=512, hop=256, finestra sqrt-Hann), stima del rumore per
# bin con "minimum statistics" semplificato (Martin 2001): minimo del periodogramma smussato su una
# finestra scorrevole di U sotto-finestre da V hop (~1,5 s), corretto da un fattore di polarizzazione;
# guadagno di Wiener "decision-directed" (Ephraim-Malah) con pavimento; indicatore di parlato.
import sys, math, numpy as np
ALPHA_P=0.85; U=8; WIN_S=1.5
class Tracker:
    def __init__(s,nb,V): s.V=V; s.k=0; s.cur=np.full(nb,np.inf); s.mins=[]; s.P=None
    def update(s,X2):
        s.P=X2.copy() if s.P is None else ALPHA_P*s.P+(1-ALPHA_P)*X2
        s.cur=np.minimum(s.cur,s.P); s.k+=1
        if s.k==s.V:
            s.mins.append(s.cur); s.mins=s.mins[-U:]; s.cur=np.full_like(s.cur,np.inf); s.k=0
        m=s.cur.copy()
        for q in s.mins: m=np.minimum(m,q)
        return m
def nr(x, sr, N=512, hop=256, floor_db=-12., dd=0.98, bias=None, speech_thr_db=6.):
    win=np.sqrt(np.hanning(N+1)[:N]); nb=N//2+1
    V=max(1,round(WIN_S*sr/hop/U)); tr=Tracker(nb,V)
    Gprev=np.ones(nb); Xprev2=np.zeros(nb); gmin=10**(floor_db/20); bias=BIAS if bias is None else bias
    xp=np.concatenate([np.zeros(N-hop),x,np.zeros(N)])
    y=np.zeros(len(xp)); fr=np.fft.rfftfreq(N,1/sr); band=(fr>=300)&(fr<=4000); speech=[]
    for s in range(0,len(xp)-N+1,hop):
        X=np.fft.rfft(xp[s:s+N]*win); X2=np.abs(X)**2
        noise=tr.update(X2)*bias+1e-20
        gamma=X2/noise
        xi=dd*(Gprev**2)*Xprev2/noise+(1-dd)*np.maximum(gamma-1,0)
        G=np.maximum(xi/(1+xi),gmin); Gprev=G; Xprev2=X2
        y[s:s+N]+=np.fft.irfft(X*G,N)*win
        speech.append(tr.P[band].sum()>noise[band].sum()*10**(speech_thr_db/10))
    return y[N-hop:N-hop+len(x)], np.repeat(np.array(speech),hop)[:len(x)]
BIAS=2.3
if __name__=="__main__":
    rng=np.random.default_rng(1); sr=44100; x=rng.normal(0,.01,sr*12)
    N=512;hop=256;win=np.sqrt(np.hanning(N+1)[:N]); tr=Tracker(257,max(1,round(WIN_S*sr/hop/U))); r=[]
    for s in range(0,len(x)-N,hop):
        X2=np.abs(np.fft.rfft(x[s:s+N]*win))**2; m=tr.update(X2)
        if s>sr*3: r.append((m.mean(), X2.mean()))
    r=np.array(r); print("bias",r[:,1].mean()/r[:,0].mean())
