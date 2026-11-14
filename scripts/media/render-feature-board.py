#!/usr/bin/env python3
"""Render the Doran feature board from reviewed actual app captures.
Original graphics, composited real captures, and a time-driven motion system.
No device access or host playback. See README for commercial design references.
"""
import argparse,base64,io,json,math,subprocess,sys
from pathlib import Path
from functools import lru_cache
import numpy as np
from PIL import Image,ImageDraw,ImageFont,ImageFilter
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'artifacts/private/final-media'; PUB=ROOT/'docs/images'
FONT=ROOT/'apps/mobile/assets/fonts/SUIT-Variable.ttf'
INK='#20292F'; BLUE='#315C78'; SKY='#9CC7DE'; FOG='#F5F7F8'; WHITE='#FFFFFF'; MUTED='#65737B'
@lru_cache(maxsize=100)
def font(n,w=500):
 f=ImageFont.truetype(str(FONT),int(n));f.set_variation_by_axes([w]);return f
def text(im,xy,s,n=32,w=500,c=INK,leading=1.25):
 d=ImageDraw.Draw(im);x,y=xy
 for ln in s.split('\n'):d.text((round(x),round(y)),ln,font=font(n,w),fill=c,anchor='lt');y+=n*leading

def logo(im,x,y,s=50,c=BLUE):
 d=ImageDraw.Draw(im);sw=max(2,round(s/16))
 d.rounded_rectangle((x,y,x+s*.6,y+s*.42),s*.1,outline=c,width=sw)
 d.line((x+s*.2,y+s*.42,x+s*.15,y+s*.55),fill=c,width=sw)
 for yy,length in [(0.64,.6),(.82,.6),(1,.4)]:d.line((x+s*.35,y+s*yy,x+s*(.35+length),y+s*yy),fill=c,width=sw)

def bg(w,h,dark=False):
 # A cool, restrained product stage; depth is concentrated behind the devices.
 yy,xx=np.mgrid[0:h,0:w];z=np.exp(-(((xx-w*.73)/(w*.45))**2+((yy-h*.6)/(h*.6))**2))
 a=np.array([245,247,248] if not dark else [32,41,47]);b=np.array([239,244,247] if not dark else [29,45,55])
 rgb=a[None,None,:]+z[:,:,None]*(b-a)[None,None,:]
 return Image.fromarray(np.uint8(rgb)).convert('RGBA')

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--captures-reviewed',action='store_true',help='Confirm example captures contain no private data')
parser.add_argument('--car',type=Path,default=ROOT/'artifacts/private/autonomous-demo/car-b/car-b-correction.png')
parser.add_argument('--today',type=Path,default=ROOT/'artifacts/private/autonomous-demo/mobile/b-today.png')
parser.add_argument('--compare',type=Path,default=ROOT/'artifacts/private/autonomous-demo/mobile/b-comparison.png')
args=parser.parse_args()
if not args.captures_reviewed:parser.error('Review capture privacy and pass --captures-reviewed')
car=Image.open(args.car).convert('RGBA')
today=Image.open(args.today).convert('RGBA')
compare=Image.open(args.compare).convert('RGBA')

def rounded(im,r):
 out=im.copy();m=Image.new('L',im.size);ImageDraw.Draw(m).rounded_rectangle((0,0,im.width-1,im.height-1),r,fill=255);out.putalpha(m);return out

def surface(source,w,kind='car'):
 if kind=='phone':
  margin=15; innerw=w-30; innerh=round(innerw*source.height/source.width)
  sh=source.resize((innerw,innerh),Image.Resampling.LANCZOS)
  out=Image.new('RGBA',(w,innerh+30));d=ImageDraw.Draw(out)
  d.rounded_rectangle((0,0,w-1,out.height-1),w*.105,fill='#15232D',outline='#647580',width=2)
  out.alpha_composite(rounded(sh,w*.075),(margin,margin));return out
 margin=18;sh=source.resize((w-36,round((w-36)*source.height/source.width)),Image.Resampling.LANCZOS)
 out=Image.new('RGBA',(w,sh.height+36));d=ImageDraw.Draw(out)
 d.rounded_rectangle((0,0,w-1,out.height-1),38,fill='#14232C',outline='#6C8795',width=2)
 out.alpha_composite(rounded(sh,25),(margin,margin));return out

@lru_cache(maxsize=80)
def device(kind,w):return surface(car if kind=='car' else today,w,kind)

def paste_shadow(canvas,obj,x,y,angle=0,opacity=1,shadow=42):
 if angle:obj=obj.rotate(angle,resample=Image.Resampling.BICUBIC,expand=True)
 if opacity<1:obj=obj.copy();obj.putalpha(obj.getchannel('A').point(lambda a:round(a*max(0,opacity))))
 pad=shadow*3;mask=Image.new('L',(obj.width+pad*2,obj.height+pad*2));mask.paste(obj.getchannel('A'),(pad,pad))
 mask=mask.filter(ImageFilter.GaussianBlur(shadow*.45));shade=Image.new('RGBA',mask.size,(14,35,49,0));shade.putalpha(mask.point(lambda a:round(a*.09)))
 canvas.alpha_composite(shade,(round(x-pad),round(y-pad+shadow*.2)))
 canvas.alpha_composite(obj,(round(x),round(y)))

def crop_card(source,box,width):
 im=source.crop(box);im=im.resize((width,round(im.height*width/im.width)),Image.Resampling.LANCZOS)
 return rounded(im,22)

# Actual Korean record excerpts; source text is unchanged.
records=crop_card(today,(60,1310,1020,2140),900)
journal=crop_card(compare,(65,1190,1015,1760),620)

def curve(canvas,pts,color=SKY,width=5,progress=1):
 d=ImageDraw.Draw(canvas);a,b,c,e=map(np.array,pts);q=[]
 for t in np.linspace(0,max(.001,min(1,progress)),160):
  p=(1-t)**3*a+3*(1-t)**2*t*b+3*(1-t)*t*t*c+t**3*e;q.append(tuple(p))
 d.line(q,fill=color,width=width,joint='curve')
 if progress>0:
  x,y=q[-1];r=width*1.4;d.ellipse((x-r,y-r,x+r,y+r),fill=color)

def pill(canvas,x,y,s,dark=False):
 f=font(26,600);bw=math.ceil(f.getlength(s))+40;d=ImageDraw.Draw(canvas)
 d.rounded_rectangle((x,y,x+bw,y+52),26,fill=INK if dark else WHITE)
 text(canvas,(x+20,y+12),s,26,600,WHITE if dark else BLUE)

def board():
 im=bg(2400,1600)
 logo(im,100,77,50);text(im,(176,77),'도란',52,600)
 text(im,(100,205),'이동하는 동안,\n오늘을 이야기해요.',112,600,leading=1.22)
 text(im,(106,498),'편하게 나눈 대화로 생각을 풀고,\n다시 꺼내 읽을 하루의 기록을 남겨요.',35,400,MUTED)
 curve(im,[(380,1090),(600,1440),(1400,1450),(1330,1070)],SKY,8)
 paste_shadow(im,device('car',1040),70,660,angle=3,shadow=18)
 text(im,(1400,150),'나눈 대화를 기록으로',38,600)
 paste_shadow(im,records,1400,230,shadow=14)
 text(im,(1400,1120),'하루의 이야기로 다시 읽어요',38,600)
 paste_shadow(im,journal,1400,1200,shadow=14)
 text(im,(102,1440),'말로 풀어보는 오늘의 생각',33,600)
 text(im,(102,1490),'이야기를 나누고, 나중에 다시 꺼내 읽어요.',26,400,MUTED)
 text(im,(2210,1545),'예시 기록',24,400,MUTED)
 return im.convert('RGB')

def svg_for_board(image):
 from xml.sax.saxutils import escape
 parts=['<svg xmlns="http://www.w3.org/2000/svg" width="2400" height="1600" viewBox="0 0 2400 1600">','<title>도란 기능 소개</title>','<desc>실제 앱의 차량 화면과 한국어 기록 부분을 확대한 소개 보드.</desc>','<defs><radialGradient id="stage" cx="73%" cy="60%" r="70%"><stop stop-color="#EFF4F7"/><stop offset="1" stop-color="#F5F7F8"/></radialGradient><filter id="shadow" x="-15%" y="-15%" width="130%" height="130%"><feDropShadow dx="0" dy="3" stdDeviation="7" flood-color="#0E2331" flood-opacity=".09"/></filter></defs>','<rect width="2400" height="1600" fill="url(#stage)"/>','<path d="M380 1090 C600 1440 1400 1450 1330 1070" fill="none" stroke="#9CC7DE" stroke-width="8"/>']
 def txt(x,y,value,size,weight=500,color=INK):
  for i,line in enumerate(value.split('\n')):parts.append(f'<text x="{x}" y="{y+i*size*1.22}" dominant-baseline="text-before-edge" font-family="SUIT" font-weight="{weight}" font-size="{size}" fill="{color}">{escape(line)}</text>')
 def asset(im,x,y):
  buf=io.BytesIO();im.save(buf,'PNG');enc=base64.b64encode(buf.getvalue()).decode();parts.append(f'<image x="{x}" y="{y}" width="{im.width}" height="{im.height}" filter="url(#shadow)" href="data:image/png;base64,{enc}"/>')
 txt(100,77,'도란',52,600);txt(100,205,'이동하는 동안,\n오늘을 이야기해요.',112,600)
 txt(106,498,'편하게 나눈 대화로 생각을 풀고,\n다시 꺼내 읽을 하루의 기록을 남겨요.',35,400,MUTED)
 asset(device('car',1040).rotate(3,resample=Image.Resampling.BICUBIC,expand=True),70,660)
 txt(1400,150,'나눈 대화를 기록으로',38,600);asset(records,1400,230)
 txt(1400,1120,'하루의 이야기로 다시 읽어요',38,600);asset(journal,1400,1200)
 txt(102,1440,'말로 풀어보는 오늘의 생각',33,600);txt(102,1490,'이야기를 나누고, 나중에 다시 꺼내 읽어요.',26,400,MUTED);txt(2210,1545,'예시 기록',24,400,MUTED)
 parts.append('</svg>');return '\n'.join(parts)

def main():
 PUB.mkdir(parents=True,exist_ok=True)
 b=board();b.save(PUB/'doran-features.png');(PUB/'doran-features.svg').write_text(svg_for_board(b))
 print('BOARD_READY')
if __name__=='__main__':main()
