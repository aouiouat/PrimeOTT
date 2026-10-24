#!/usr/bin/env python3
"""Apply the requested PrimeOTT UI patches. Run from the project root: python patcher.py"""
from pathlib import Path
from datetime import datetime
import re, shutil, sys

ROOT = Path(__file__).resolve().parent
base_candidates = [ROOT / 'app/src/main', ROOT / 'src/main']
BASE = next((p for p in base_candidates if (p / 'java/com/primeott/app/MainActivity.kt').is_file()), None)
if BASE is None:
    print('ERROR: Put patcher.py in the PrimeOTT project root (folder containing app/ or src/).')
    sys.exit(1)
paths = {
 'main': BASE / 'java/com/primeott/app/MainActivity.kt',
 'sections': BASE / 'java/com/primeott/app/presentation/sections/AppSectionsFragments.kt',
 'detail': BASE / 'java/com/primeott/app/presentation/detail/ContentDetailActivity.kt',
 'layout': BASE / 'res/layout/fragment_home.xml',
 'helper': BASE / 'java/com/primeott/app/presentation/sections/PrimeSkeletonHelper.kt',
}
for key in ('main','sections','detail','layout'):
    if not paths[key].is_file():
        print(f'ERROR: Required file not found: {paths[key].relative_to(ROOT)}')
        sys.exit(1)
original = {k: paths[k].read_text(encoding='utf-8') for k in ('main','sections','detail','layout')}
updated = dict(original)

def once(s, old, new, label):
    n=s.count(old)
    if n != 1: raise RuntimeError(f'{label}: expected one match, found {n}; no files were changed.')
    return s.replace(old,new,1)
def regex_once(s, pat, repl, label):
    out,n=re.subn(pat,repl,s,count=1,flags=re.S)
    if n != 1: raise RuntimeError(f'{label}: expected source block was not found; no files were changed.')
    return out

# 1. Dark background with subtle upper-left ambient glow.
main=updated['main']
if 'class FlixVisionStandardBgDrawable' not in main: raise RuntimeError('Background drawable class missing; no files changed.')
if 'Color.parseColor("#0F151E")' not in main:
    main=regex_once(main,r'class FlixVisionStandardBgDrawable\s*:\s*Drawable\(\)\s*\{.*?\n\}', '''class FlixVisionStandardBgDrawable : Drawable() {
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val ambientGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat().coerceAtLeast(1f)
        val h = b.height().toFloat().coerceAtLeast(1f)
        basePaint.shader = LinearGradient(0f, 0f, w, h * 0.6f,
            intArrayOf(Color.parseColor("#0F151E"), Color.parseColor("#080B10"), Color.parseColor("#030406"), Color.BLACK),
            floatArrayOf(0f, 0.35f, 0.70f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, basePaint)
        ambientGlowPaint.shader = RadialGradient(w * 0.12f, h * 0.08f, kotlin.math.max(w, h) * 0.72f,
            intArrayOf(Color.parseColor("#55222D3B"), Color.parseColor("#2818202B"), Color.TRANSPARENT),
            floatArrayOf(0f, 0.48f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, ambientGlowPaint)
    }
    override fun setAlpha(alpha: Int) { basePaint.alpha = alpha; ambientGlowPaint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { basePaint.colorFilter = colorFilter; ambientGlowPaint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.OPAQUE
}''', 'background drawable')
if 'import android.graphics.RadialGradient' not in main:
    main=main.replace('import android.graphics.PixelFormat', 'import android.graphics.PixelFormat\nimport android.graphics.RadialGradient', 1)
updated['main']=main

# 2. Home: immediate skeletons and tilted infinite carousel.
s=updated['sections']
if 'PrimeSkeletonHelper.VodSkeletonAdapter(6' not in s:
    anchor='''        binding.rvHomeChannels.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvHomeSeries.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvRecentWatched.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)'''
    s=once(s,anchor,anchor+'''\n        binding.rvHomeChannels.adapter = PrimeSkeletonHelper.VodSkeletonAdapter(6, isGrid = false, isSeries = false)
        binding.rvHomeSeries.adapter = PrimeSkeletonHelper.VodSkeletonAdapter(6, isGrid = false, isSeries = true)''','Home skeleton setup')
# Replace only transformer configuration, preserving page callback.
s=regex_once(s,r'''        binding\.heroViewPager\.apply \{.*?            setPageTransformer\(transformer\)
            registerOnPageChangeCallback''','''        binding.heroViewPager.apply {
            clipToPadding = false
            clipChildren = false
            offscreenPageLimit = 3
            (getChildAt(0) as? RecyclerView)?.apply {
                overScrollMode = RecyclerView.OVER_SCROLL_NEVER
                clipToPadding = false
                clipChildren = false
            }
            val d = resources.displayMetrics.density
            val transformer = CompositePageTransformer().apply {
                addTransformer(MarginPageTransformer((10 * d).toInt()))
                addTransformer { page, position ->
                    val p = position.coerceIn(-1.5f, 1.5f)
                    val absP = kotlin.math.abs(p)
                    val center = (1f - absP).coerceAtLeast(0f)
                    page.pivotX = page.width * 0.5f
                    page.pivotY = page.height * 0.85f
                    page.rotation = p * 6.5f
                    page.translationY = absP * (20f * d)
                    page.scaleX = 0.88f + center * 0.12f
                    page.scaleY = 0.85f + center * 0.15f
                    page.alpha = 0.52f + center * 0.48f
                }
            }
            setPageTransformer(transformer)
            registerOnPageChangeCallback''','3D banner transformer')
if 'val midStart =' not in s:
    s=once(s,'''                updateHeroFavoriteButtonIcon(binding)
                startHeroAutoSlider(binding)''','''                val midStart = (Int.MAX_VALUE / 2) - ((Int.MAX_VALUE / 2) % heroItems.size)
                binding.heroViewPager.setCurrentItem(midStart, false)
                updateHeroFavoriteButtonIcon(binding)
                startHeroAutoSlider(binding)''','infinite banner starting position')
# use modulo for current banner item
s=s.replace('binding.heroViewPager.currentItem.coerceIn(0, heroItems.lastIndex)','binding.heroViewPager.currentItem % heroItems.size')
# auto slider increments without wrapping back to zero
s=re.sub(r'val count = heroItems.size\s*\n\s*if \(count > 1\) \{\s*val next = \(binding.heroViewPager.currentItem \+ 1\) % count\s*binding.heroViewPager.setCurrentItem\(next, true\)\s*\}', 'if (heroItems.size > 1) {\n                    binding.heroViewPager.setCurrentItem(binding.heroViewPager.currentItem + 1, true)\n                }', s, count=1)
# Infinite item count and modulo item selection specifically within HeroBannerAdapter.
idx=s.find('class HeroBannerAdapter(')
if idx < 0: raise RuntimeError('HeroBannerAdapter missing; no files changed.')
end_adapter=s.find('\n/**\n * صفحة Movies', idx)
if end_adapter < 0: raise RuntimeError('Could not find the end of HeroBannerAdapter; no files changed.')
head, hero_block, rest = s[:idx], s[idx:end_adapter], s[end_adapter:]
hero_block=hero_block.replace('override fun getItemCount(): Int = items.size','override fun getItemCount(): Int = if (items.isEmpty()) 0 else Int.MAX_VALUE',1)
hero_block=hero_block.replace('val item = items[position]','val item = items[position % items.size]',1)
hero_block=hero_block.replace('''        if (item.posterUrl.isNotEmpty()) {
            Glide.with(holder.itemView.context)
                .load(item.posterUrl)
                .centerCrop()
                .into(holder.ivHeroPoster)
        }''','''        val heroPlaceholder = PrimeSkeletonHelper.createCardPlaceholderDrawable(holder.itemView.context,
            if (item.isSeries) PrimeSkeletonHelper.MediaSkeletonType.SERIES else PrimeSkeletonHelper.MediaSkeletonType.MOVIE)
        if (item.posterUrl.isNotEmpty()) {
            Glide.with(holder.itemView.context).load(item.posterUrl).placeholder(heroPlaceholder).error(heroPlaceholder).centerCrop().into(holder.ivHeroPoster)
        } else holder.ivHeroPoster.setImageDrawable(heroPlaceholder)''',1)
s=head+hero_block+rest
# Ensure the normal VOD adapter keeps its finite list and direct position indexing.
vod_idx=s.find('class VodGridAdapter(')
if vod_idx >= 0:
    vod_end=s.find('\nclass ScheduleFragment',vod_idx)
    if vod_end < 0: raise RuntimeError('Could not find the end of VodGridAdapter; no files were changed.')
    vhead,vblock,vrest=s[:vod_idx],s[vod_idx:vod_end],s[vod_end:]
    vblock=vblock.replace('override fun getItemCount(): Int = if (items.isEmpty()) 0 else Int.MAX_VALUE','override fun getItemCount() = items.size')
    vblock=vblock.replace('val item = items[position % items.size]','val item = items[position]')
    s=vhead+vblock+vrest
updated['sections']=s

# 3. Preserve the existing XML layout; only tune banner space and See All colors.
xml=updated['layout']
xml = re.sub(r'(<androidx\.viewpager2\.widget\.ViewPager2\b.*?android:id="@\+id/heroViewPager".*?android:layout_height=")[^"]+(")', r'\g<1>400dp\2', xml, count=1, flags=re.S)
xml=xml.replace('android:paddingHorizontal="38dp"','android:paddingHorizontal="52dp"').replace('android:paddingHorizontal="20dp"','android:paddingHorizontal="52dp"')
for view_id in ('btnSeeAllMovies','btnSeeAllSeries'):
    def color_see_all(match):
        tag = match.group(0)
        if f'@+id/{view_id}' not in tag:
            return tag
        if 'android:textColor=' in tag:
            return re.sub(r'android:textColor="[^"]+"', 'android:textColor="#E50914"', tag, count=1)
        return tag.replace('android:text="See All"', 'android:text="See All" android:textColor="#E50914"', 1)
    xml = re.sub(r'<TextView\b[^>]*>', color_see_all, xml, flags=re.S)
updated['layout']=xml

# 4. Detail page placeholders and actor poster placeholders.
d=updated['detail']
old='''        val cachedDetails = detailsCache[vod.id]
        if (cachedDetails != null) {
            bindMovieFullDetailsToUi(vod, cachedDetails)
        } else {
            crewMetadataContainer.visibility = View.GONE
        }'''
if 'PrimeSkeletonHelper.CastSkeletonAdapter' not in d:
    d=once(d,old,'''        val cachedDetails = detailsCache[vod.id]
        if (cachedDetails != null) {
            bindMovieFullDetailsToUi(vod, cachedDetails)
        } else {
            crewMetadataContainer.visibility = View.GONE
            tvCastHeader.visibility = View.VISIBLE
            rvCast.visibility = View.VISIBLE
            rvCast.adapter = com.primeott.app.presentation.sections.PrimeSkeletonHelper.CastSkeletonAdapter(6)
            rvRecommended.adapter = com.primeott.app.presentation.sections.PrimeSkeletonHelper.VodSkeletonAdapter(itemCountToShow = 5, isGrid = false, isSeries = vod.isSeries)
            rvMoreLike.adapter = com.primeott.app.presentation.sections.PrimeSkeletonHelper.VodSkeletonAdapter(itemCountToShow = 5, isGrid = false, isSeries = vod.isSeries)
        }''','detail page loading skeletons')
if 'val actorSkeleton =' not in d:
    d=once(d,'''        val img = item.posterUrl.ifEmpty { item.backdropUrl }
        if (img.isNotEmpty()) {
            Glide.with(holder.itemView.context)
                .load(img)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .centerCrop()
                .into(holder.ivPoster)
        }''','''        val actorSkeleton = com.primeott.app.presentation.sections.PrimeSkeletonHelper.createCardPlaceholderDrawable(
            holder.itemView.context,
            if (item.isSeries) com.primeott.app.presentation.sections.PrimeSkeletonHelper.MediaSkeletonType.SERIES
            else com.primeott.app.presentation.sections.PrimeSkeletonHelper.MediaSkeletonType.MOVIE
        )
        val img = item.posterUrl.ifEmpty { item.backdropUrl }
        if (img.isNotEmpty()) {
            Glide.with(holder.itemView.context).load(img).placeholder(actorSkeleton).error(actorSkeleton)
                .diskCacheStrategy(DiskCacheStrategy.ALL).centerCrop().into(holder.ivPoster)
        } else holder.ivPoster.setImageDrawable(actorSkeleton)''','actor credits skeleton')
if 'rvActorCreditsGrid.adapter = com.primeott.app.presentation.sections.PrimeSkeletonHelper.VodSkeletonAdapter' not in d:
    d=once(d,'''            rvActorCreditsGrid.adapter = null
            tvActorMoviesTab.text = "Movies • ..."''','''            rvActorCreditsGrid.adapter = com.primeott.app.presentation.sections.PrimeSkeletonHelper.VodSkeletonAdapter(itemCountToShow = 9, isGrid = true, isSeries = false)
            tvActorMoviesTab.text = "Movies • ..."''','actor page loading skeleton')
updated['detail']=d

# Helper source. Kept self-contained and uses only Android/RecyclerView APIs already present in this app.
HELPER_SOURCE = r'''package com.primeott.app.presentation.sections

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.min

object PrimeSkeletonHelper {
    enum class MediaSkeletonType { MOVIE, SERIES, CAST }
    fun createCardPlaceholderDrawable(context: Context, type: MediaSkeletonType = MediaSkeletonType.MOVIE): Drawable {
        val d = context.resources.displayMetrics.density
        return object : Drawable() {
            private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0D1015") }
            private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1D232E"); style = Paint.Style.STROKE; strokeWidth = 1.4f*d }
            private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#151A22"); style = Paint.Style.STROKE; strokeWidth = 1.1f*d }
            private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#B8C2D1"); style = Paint.Style.STROKE; strokeWidth = 2.4f*d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
            private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#B8C2D1") }
            override fun draw(canvas: Canvas) {
                val w=bounds.width().toFloat(); val h=bounds.height().toFloat(); if(w<=0f||h<=0f)return
                val cx=w/2f; val cy=h/2f
                if(type==MediaSkeletonType.CAST){ val r=min(w,h)/2f-2f*d; canvas.drawCircle(cx,cy,r,bg); canvas.drawCircle(cx,cy,r,stroke); canvas.drawCircle(cx,cy-r*.16f,r*.28f,icon); canvas.drawArc(RectF(cx-r*.48f,cy+r*.18f,cx+r*.48f,cy+r*.85f),195f,150f,false,icon); return }
                val corner=12f*d; canvas.drawRoundRect(RectF(d,d,w-d,h-d),corner,corner,bg); canvas.drawRoundRect(RectF(d,d,w-d,h-d),corner,corner,stroke)
                val inset=6f*d; canvas.drawRoundRect(RectF(inset,inset,w-inset,h-inset),corner*.75f,corner*.75f,inner)
                val size=min(w,h)*.34f; val l=cx-size/2f; val t=cy-size*.45f; val r=cx+size/2f; val b=cy+size*.45f
                if(type==MediaSkeletonType.MOVIE){ val bt=t+size*.28f; canvas.drawRoundRect(RectF(l,bt,r,b),3f*d,3f*d,icon); canvas.drawRoundRect(RectF(l,t,r,bt),2.5f*d,2.5f*d,icon); val step=size/3.2f; for(i in 1..2){val x=l+i*step;canvas.drawLine(x-3f*d,bt,x+4f*d,t,icon)} }
                else { val tt=t+size*.18f; val tb=b-size*.1f; canvas.drawRoundRect(RectF(l,tt,r,tb),4f*d,4f*d,icon); canvas.drawLine(cx,tt,cx-size*.22f,t,icon); canvas.drawLine(cx,tt,cx+size*.22f,t,icon); val p=size*.14f; val py=(tt+tb)/2; canvas.drawPath(Path().apply{moveTo(cx-p*.6f,py-p);lineTo(cx+p,py);lineTo(cx-p*.6f,py+p);close()},fill) }
            }
            override fun setAlpha(alpha:Int){bg.alpha=alpha;stroke.alpha=alpha;inner.alpha=alpha;icon.alpha=alpha;fill.alpha=alpha}
            override fun setColorFilter(colorFilter:ColorFilter?){bg.colorFilter=colorFilter;stroke.colorFilter=colorFilter;inner.colorFilter=colorFilter;icon.colorFilter=colorFilter;fill.colorFilter=colorFilter}
            @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
        }
    }
    private fun pulse(view:View,delay:Long){ObjectAnimator.ofFloat(view,"alpha",.92f,.38f,.92f).apply{duration=1250L;startDelay=delay;repeatCount=ValueAnimator.INFINITE;start()}}
    class VodSkeletonAdapter(private val itemCountToShow:Int=9,private val isGrid:Boolean=false,private val isSeries:Boolean=false):RecyclerView.Adapter<VodSkeletonAdapter.VH>(){
        class VH(val root:View):RecyclerView.ViewHolder(root)
        override fun onCreateViewHolder(parent:ViewGroup,viewType:Int):VH{val c=parent.context;val d=c.resources.displayMetrics.density;val wrap=LinearLayout(c).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;layoutParams=if(isGrid)ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{val m=(5.5f*d).toInt();setMargins(m,m,m,(10*d).toInt())}else ViewGroup.MarginLayoutParams((116*d).toInt(),ViewGroup.LayoutParams.WRAP_CONTENT).apply{val m=(5.5f*d).toInt();setMargins(m,(4*d).toInt(),m,(4*d).toInt())}}
            wrap.addView(View(c).apply{background=createCardPlaceholderDrawable(c,if(isSeries)MediaSkeletonType.SERIES else MediaSkeletonType.MOVIE);layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,(164*d).toInt())})
            fun bar(w:Int,h:Int,color:String,top:Int)=View(c).apply{background=GradientDrawable().apply{cornerRadius=5f*d;setColor(Color.parseColor(color))};layoutParams=LinearLayout.LayoutParams((w*d).toInt(),(h*d).toInt()).apply{topMargin=(top*d).toInt()}}
            wrap.addView(bar(86,10,"#1A202C",7));wrap.addView(bar(42,8,"#131822",5));return VH(wrap)}
        override fun getItemCount()=itemCountToShow
        override fun onBindViewHolder(holder:VH,position:Int){pulse(holder.root,(position%6)*90L)}
    }
    class CastSkeletonAdapter(private val count:Int=6):RecyclerView.Adapter<CastSkeletonAdapter.VH>(){
        class VH(val root:View):RecyclerView.ViewHolder(root)
        override fun onCreateViewHolder(parent:ViewGroup,viewType:Int):VH{val c=parent.context;val d=c.resources.displayMetrics.density;val root=LinearLayout(c).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;layoutParams=ViewGroup.MarginLayoutParams((96*d).toInt(),ViewGroup.LayoutParams.WRAP_CONTENT).apply{marginEnd=(12*d).toInt()}}
            root.addView(View(c).apply{background=createCardPlaceholderDrawable(c,MediaSkeletonType.CAST);layoutParams=LinearLayout.LayoutParams((78*d).toInt(),(78*d).toInt())})
            fun bar(w:Int,h:Int,color:String,top:Int)=View(c).apply{background=GradientDrawable().apply{cornerRadius=5f*d;setColor(Color.parseColor(color))};layoutParams=LinearLayout.LayoutParams((w*d).toInt(),(h*d).toInt()).apply{topMargin=(top*d).toInt()}}
            root.addView(bar(68,10,"#1D2432",8));root.addView(bar(46,8,"#141923",5));return VH(root)}
        override fun getItemCount()=count
        override fun onBindViewHolder(holder:VH,position:Int){pulse(holder.root,position*100L)}
    }
}
'''

# Validate helper availability before any writes.
helper_path=paths['helper']
if helper_path.exists():
    if 'object PrimeSkeletonHelper' not in helper_path.read_text(encoding='utf-8'):
        raise RuntimeError('PrimeSkeletonHelper.kt already exists but is not the expected helper; no files were changed.')
    helper_new=None
else: helper_new=HELPER_SOURCE

changed=[]
for k in ('main','sections','detail','layout'):
    if updated[k]!=original[k]: changed.append(paths[k])
if helper_new is not None: changed.append(helper_path)
if not changed:
    print('No changes needed; requested patches appear to be present.')
    sys.exit(0)
backup=ROOT/('patcher_backup_'+datetime.now().strftime('%Y%m%d_%H%M%S'))
backup.mkdir(parents=True,exist_ok=False)
for p in changed:
    if p.exists():
        dest=backup/p.relative_to(ROOT); dest.parent.mkdir(parents=True,exist_ok=True); shutil.copy2(p,dest)
try:
    for k in ('main','sections','detail','layout'):
        if updated[k]!=original[k]: paths[k].write_text(updated[k],encoding='utf-8',newline='')
    if helper_new is not None:
        helper_path.parent.mkdir(parents=True,exist_ok=True); helper_path.write_text(helper_new,encoding='utf-8',newline='')
except Exception:
    print('ERROR while writing; backups are at',backup)
    raise
print('PATCH APPLIED. Changed files:')
for p in changed: print(' -',p.relative_to(ROOT))
print('Backup:',backup.relative_to(ROOT))
print('Now run Android Studio > Build > Make Project. Review any compiler errors before installing.')
