# Prototipo della nuova catena "Amplifica voce" (mono), riferimento per il porting Kotlin.
# Stadi: passa-alto 120 Hz (Butterworth 4°), scaffale basso -8 dB a 250 Hz, campana -4 dB a 450 Hz,
# campana +3 dB a 3 kHz, scaffale alto +6 dB da 2 kHz, livellatore lento, compressore morbido,
# limitatore di picco a -2 dBFS e saturazione morbida finale.
import sys, math, numpy as np
sys.path.insert(0, sys.argv[0].rsplit('/',1)[0]); from chain import biq_hp,biq_pk,biq_ls,lfilter
def db2lin(d): return 10**(d/20)
def process(x,sr,P,flags=None):
    for c in [biq_hp(sr,P['hp'],.5411961001461970),biq_hp(sr,P['hp'],1.3065629648763766),
              biq_ls(sr,P['low_f'],.707,P['low_g'],False),
              biq_pk(sr,P['mud_f'],P['mud_q'],P['mud_g']),
              biq_pk(sr,P['pres_f'],P['pres_q'],P['pres_g']),
              biq_ls(sr,P['shelf_f'],.707,P['shelf_g'],True)]:
        x=lfilter(c,x)
    y=np.empty_like(x)
    a_lvl=math.exp(-1/(P['lvl_tau']*sr))
    a_gu=math.exp(-1/(P['gain_up']*sr)); a_gd=math.exp(-1/(P['gain_dn']*sr))
    a_ca=math.exp(-1/(.010*sr)); a_cr=math.exp(-1/(.200*sr)); a_lr=math.exp(-1/(.060*sr))
    ms=db2lin(P['target'])**2; g=0.; cred=0.; lenv=0.; ceil=db2lin(P['ceiling'])
    for i,v in enumerate(x):
        ms=v*v+a_lvl*(ms-v*v); lvl=10*math.log10(ms+1e-20)
        if lvl>P['gate'] and (flags is None or flags[i]):   # nel silenzio il guadagno resta fermo: non alzo il rumore di fondo
            t=min(P['max_gain'],max(P['min_gain'],P['target']-lvl))
            g=t+(a_gu if t>g else a_gd)*(g-t)
        s=v*db2lin(g)
        a=abs(s); l=20*math.log10(a) if a>1e-9 else -180
        t=(l-P['c_thr'])*(1-1/P['c_ratio']) if l>P['c_thr'] else 0.
        cred=t+(a_ca if t>cred else a_cr)*(cred-t)
        s*=db2lin(-cred)
        a=abs(s); lenv=a if a>lenv else a+a_lr*(lenv-a)
        if lenv>ceil: s*=ceil/lenv
        y[i]=s
    m=np.abs(y); out=np.where(m<=.8,m,.8+.2*np.tanh((m-.8)/.2)); return np.sign(y)*out
BASE=dict(hp=120,low_f=250,low_g=-8.,mud_f=450,mud_q=1.,mud_g=-4.,pres_f=3000,pres_q=1.,pres_g=3.,
          shelf_f=2000,shelf_g=6.,lvl_tau=.4,gain_up=1.5,gain_dn=.3,target=-17.,max_gain=12.,min_gain=-6.,
          gate=-50.,c_thr=-12.,c_ratio=2.5,ceiling=-2.)
if __name__=="__main__":
    import json
    P=dict(BASE); P.update(json.loads(sys.argv[3]) if len(sys.argv)>3 else {})
    x=np.fromfile(sys.argv[1],dtype=np.float32).astype(np.float64)
    flags=None
    if P.pop('nr',False):
        from nr import nr
        x,flags=nr(x,44100,floor_db=P.pop('nr_floor',-12.),speech_thr_db=P.pop('nr_thr',6.))
    else:
        P.pop('nr_floor',None); P.pop('nr_thr',None)
    process(x,44100,P,flags).astype(np.float32).tofile(sys.argv[2])
