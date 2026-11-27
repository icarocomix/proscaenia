# Porting fedele in Python della catena Kotlin VoiceBoostDsp (mono), per misurarne l'effetto.
import numpy as np, math
def biq_hp(sr,f,q):
    w=2*math.pi*min(f,sr*.45)/sr; al=math.sin(w)/(2*q); c=math.cos(w); a0=1+al
    return [(1+c)/2/a0,-(1+c)/a0,(1+c)/2/a0,-2*c/a0,(1-al)/a0]
def biq_pk(sr,f,q,g):
    w=2*math.pi*min(f,sr*.45)/sr; A=math.sqrt(10**(g/20)); al=math.sin(w)/(2*q); c=math.cos(w); a0=1+al/A
    return [(1+al*A)/a0,-2*c/a0,(1-al*A)/a0,-2*c/a0,(1-al/A)/a0]
def biq_ls(sr,f,q,g,high):
    w=2*math.pi*min(f,sr*.45)/sr; A=math.sqrt(10**(g/20)); al=math.sin(w)/(2*q); c=math.cos(w); s=2*math.sqrt(A)*al
    if high:
        b0=A*((A+1)+(A-1)*c+s); b1=-2*A*((A-1)+(A+1)*c); b2=A*((A+1)+(A-1)*c-s)
        a0=(A+1)-(A-1)*c+s; a1=2*((A-1)-(A+1)*c); a2=(A+1)-(A-1)*c-s
    else:
        b0=A*((A+1)-(A-1)*c+s); b1=2*A*((A-1)-(A+1)*c); b2=A*((A+1)-(A-1)*c-s)
        a0=(A+1)+(A-1)*c+s; a1=-2*((A-1)+(A+1)*c); a2=(A+1)+(A-1)*c-s
    return [b0/a0,b1/a0,b2/a0,a1/a0,a2/a0]
def lfilter(c,x):
    b0,b1,b2,a1,a2=c; y=np.empty_like(x); z1=z2=0.0
    for i,v in enumerate(x):
        o=b0*v+z1; z1=b1*v-a1*o+z2; z2=b2*v-a2*o; y[i]=o
    return y
def compressor(x,sr,thr=-20.,ratio=3.,att=.005,rel=.150,makeup=3.):
    sl=1-1/ratio; ac=math.exp(-1/(att*sr)); rc=math.exp(-1/(rel*sr)); s=0.0; g=np.empty_like(x)
    for i,v in enumerate(np.abs(x)):
        l=20*math.log10(v) if v>1e-9 else -180; t=(l-thr)*sl if l>thr else 0.
        k=ac if t>s else rc; s=t+k*(s-t); g[i]=10**((makeup-s)/20)
    return x*g
def limiter(x,knee=.8):
    m=np.abs(x); h=1-knee; out=np.where(m<=knee,m,knee+h*np.tanh((m-knee)/h)); return np.sign(x)*out
def current(x,sr):
    for c in [biq_hp(sr,100,.5411961001461970),biq_hp(sr,100,1.3065629648763766),biq_pk(sr,1300,1,4.5),biq_pk(sr,3000,1,4.5)]:
        x=lfilter(c,x)
    return limiter(compressor(x,sr))
if __name__=="__main__":
    import sys
    x=np.fromfile(sys.argv[1],dtype=np.float32).astype(np.float64)
    current(x,44100).astype(np.float32).tofile(sys.argv[2])
