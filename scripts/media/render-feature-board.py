#!/usr/bin/env python3
"""Render an illustrated everyday use example in the Doran visual language.
This is not an actual app capture or verification record.
No device access or host playback. See README for commercial design references.
"""
import base64,io,math
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


def mobile_example():
 im=Image.new('RGBA',(900,1800),FOG);d=ImageDraw.Draw(im)
 text(im,(50,35),'19:42',26,600);text(im,(730,35),'•••',26,600)
 logo(im,50,105,52);text(im,(130,105),'도란',53,600)
 text(im,(50,245),'11월 15일',68,600);text(im,(50,355),'그날 나눈 이야기를 한 편으로',31,400,MUTED)
 d.rounded_rectangle((50,450,850,1110),45,fill=WHITE)
 d.line((92,452,808,452),fill=SKY,width=7)
 text(im,(90,510),'하루 일기',48,600)
 text(im,(90,610),'오늘 회의에서 준비한 의견을 말했다.\n말하기 전에는 긴장했지만,\n끝나고 나니 후련했다.\n퇴근길에 도란과 이야기하며\n그 마음을 다시 돌아봤다.',36,400,leading=1.65)
 d.rounded_rectangle((90,975,310,1060),24,outline='#D8E0E4',width=2);text(im,(145,1000),'다듬기',32,600,BLUE)
 text(im,(50,1210),'개별 기록 1개',36,600);d.ellipse((52,1308,68,1324),fill=BLUE);text(im,(95,1300),'18:35',28,400,MUTED)
 text(im,(95,1370),'회의에서 의견을 말한 날.\n긴장했던 마음과 후련함을 이야기했다.',32,400,leading=1.5)
 d.rectangle((0,1610,900,1800),fill=WHITE)
 for x,label in [(150,'오늘'),(450,'기록'),(750,'설정')]:
  color=BLUE if label=='오늘' else MUTED
  if label=='오늘':
   d.rounded_rectangle((x-24,1650,x+24,1692),5,outline=color,width=4);d.line((x-24,1662,x+24,1662),fill=color,width=3)
   for xx in [x-12,x+12]:d.line((xx,1644,xx,1658),fill=color,width=4)
  elif label=='기록':
   d.rectangle((x-26,1652,x+26,1692),outline=color,width=3);d.line((x,1652,x,1692),fill=color,width=3)
  else:
   for yy,dx in [(1654,-10),(1671,12),(1688,-4)]:
    d.line((x-25,yy,x+25,yy),fill=color,width=3);d.ellipse((x+dx-5,yy-5,x+dx+5,yy+5),fill=color)
  text(im,(x-26,1720),label,28,500,color)
 return im

def vehicle_example():
 im=Image.new('RGBA',(1500,950),'#1C292D');d=ImageDraw.Draw(im)
 logo(im,55,45,43,SKY);text(im,(120,48),'도란',32,600,WHITE);text(im,(1230,48),'휴대폰 연결됨',24,400,'#A8B8BF')
 d.rounded_rectangle((760,130,1435,865),35,fill='#27363D')
 text(im,(805,175),'도란의 답',26,500,SKY)
 text(im,(805,235),'준비한 의견을 말하고 나니\n후련했군요.\n어떤 의견을 이야기했어요?',37,400,WHITE,leading=1.55)
 logo(im,70,365,76,SKY);text(im,(70,485),'듣고 있어요',59,500,WHITE)
 text(im,(70,575),'이야기를 마치면 도란이 답해요',26,400,'#BAC8CE')
 d.rounded_rectangle((70,670,365,768),20,fill=SKY);text(im,(145,702),'잠시 멈춤',30,600,INK)
 d.rounded_rectangle((395,670,690,768),20,outline='#6B808C',width=2);text(im,(455,702),'여기까지 기록',29,500,WHITE)
 return im

car=vehicle_example();today=mobile_example()

def board():
 im=bg(2400,1600)
 logo(im,100,77,50);text(im,(176,77),'도란',52,600)
 text(im,(100,205),'이동하는 동안,\n오늘을 이야기해요.',112,600,leading=1.22)
 text(im,(106,498),'편하게 나눈 대화로 생각을 풀고,\n다시 꺼내 읽을 하루의 기록을 남겨요.',35,400,MUTED)
 curve(im,[(1190,1130),(1300,1230),(1470,1230),(1585,1110)],SKY,8)
 paste_shadow(im,device('car',1030),140,675,angle=2,shadow=16)
 paste_shadow(im,device('phone',520),1630,260,angle=-4,shadow=16)
 text(im,(1450,150),'퇴근길의 이야기, 집에서 다시 읽어요.',36,600)
 text(im,(100,1460),'“오늘 회의에서 준비한 의견을 말했어.\n긴장했는데 끝나고 나니까 후련하더라.”',31,400,MUTED,leading=1.45)
 text(im,(2210,1526),'사용 예시',24,400,MUTED)
 return im.convert('RGB')

def svg_for_board(image):
 from xml.sax.saxutils import escape
 parts=['<svg xmlns="http://www.w3.org/2000/svg" width="2400" height="1600" viewBox="0 0 2400 1600">','<title>도란 사용 예시</title>','<desc>실제 앱 디자인을 참고해 구성한 일상 사용 예시. 실행 화면 녹화나 검증 자료가 아닙니다.</desc>','<defs><radialGradient id="stage" cx="73%" cy="60%" r="70%"><stop stop-color="#EFF4F7"/><stop offset="1" stop-color="#F5F7F8"/></radialGradient><filter id="shadow" x="-15%" y="-15%" width="130%" height="130%"><feDropShadow dx="0" dy="3" stdDeviation="7" flood-color="#0E2331" flood-opacity=".09"/></filter></defs>','<rect width="2400" height="1600" fill="url(#stage)"/>','<path d="M1190 1130 C1300 1230 1470 1230 1585 1110" fill="none" stroke="#9CC7DE" stroke-width="8"/>']
 def txt(x,y,value,size,weight=500,color=INK):
  for i,line in enumerate(value.split('\n')):parts.append(f'<text x="{x}" y="{y+i*size*1.22}" dominant-baseline="text-before-edge" font-family="SUIT" font-weight="{weight}" font-size="{size}" fill="{color}">{escape(line)}</text>')
 def asset(im,x,y):
  buf=io.BytesIO();im.save(buf,'PNG');enc=base64.b64encode(buf.getvalue()).decode();parts.append(f'<image x="{x}" y="{y}" width="{im.width}" height="{im.height}" filter="url(#shadow)" href="data:image/png;base64,{enc}"/>')
 txt(100,77,'도란',52,600);txt(100,205,'이동하는 동안,\n오늘을 이야기해요.',112,600)
 txt(106,498,'편하게 나눈 대화로 생각을 풀고,\n다시 꺼내 읽을 하루의 기록을 남겨요.',35,400,MUTED)
 asset(device('car',1030).rotate(2,resample=Image.Resampling.BICUBIC,expand=True),140,675)
 asset(device('phone',520).rotate(-4,resample=Image.Resampling.BICUBIC,expand=True),1630,260)
 txt(1450,150,'퇴근길의 이야기, 집에서 다시 읽어요.',36,600)
 txt(100,1460,'“오늘 회의에서 준비한 의견을 말했어.\n긴장했는데 끝나고 나니까 후련하더라.”',31,400,MUTED);txt(2210,1526,'사용 예시',24,400,MUTED)
 parts.append('</svg>');return '\n'.join(parts)

def main():
 PUB.mkdir(parents=True,exist_ok=True)
 b=board();b.save(PUB/'doran-features.png');(PUB/'doran-features.svg').write_text(svg_for_board(b))
 print('BOARD_READY')
if __name__=='__main__':main()
