package com.test.compose_dragdrop_example

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.test.compose_dragdrop_example.ui.theme.MyApplicationTTTheme
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyApplicationTTTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LazyColumnDragAndDropDemo()
                }
            }
        }
    }
}

@Preview
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LazyColumnDragAndDropDemo() {
    //보여줄 리스트 -> 패러미터로 바꿀 수 있음
    var list by remember { mutableStateOf(List(50) { it }) }
    //현재 LazyColumn의 LazyList의 상태
    val listState = rememberLazyListState()
    //DragDrop 상태
    val dragDropState = rememberDragDropState(listState) { fromIndex, toIndex ->
        list = list.toMutableList().apply {
            Log.d("from - to", "$fromIndex $toIndex")
            add(toIndex, removeAt(fromIndex))
        }
    }

    LazyColumn(
        //dragContainer = 확장함수
        modifier = Modifier.dragContainer(dragDropState),
        //상태 저장
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        //레이아웃을 그려주기만 함
        itemsIndexed(list, key = { _, item -> item }) { index, item ->
            DraggableItem(dragDropState, index) { isDragging ->
                val elevation by animateDpAsState(if (isDragging) 4.dp else 1.dp)
                Card(elevation = CardDefaults.cardElevation(elevation)) {
                    Text(
                        "Item $item",
                        Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    )
                }
            }
        }
    }
}

//DragDropState를 리컴포지션시 저장하기위한 State
@Composable
fun rememberDragDropState(
    lazyListState: LazyListState,
    onMove: (Int, Int) -> Unit
): DragDropState {
    //컴포지션(init)을 종료한 후 자동으로 취소되도록 범위를 지정해줌 <- 안하면 - 컴포지션이 종료되도 코루틴이 돌것이다.
    val scope = rememberCoroutineScope()
    val state = remember(lazyListState) {
        DragDropState(
            state = lazyListState,
            onMove = onMove,
            scope = scope
        )
    }
    //key의 state가 recomposition이 될 때 블락 안에 있는 suspend fun 이 작동하도록 함
    LaunchedEffect(state) {
        while (true) {
            //scrollChannel 로 부터 콜백 받음
            val diff = state.scrollChannel.receive()
            //픽셀 값 만큼 즉시 점프
            lazyListState.scrollBy(diff)
        }
    }
    return state
}

//remember을 위해 상태, 코루틴스코프, 이동 콜백을 가져옴
class DragDropState internal constructor(
    private val state: LazyListState,
    private val scope: CoroutineScope,
    private val onMove: (Int, Int) -> Unit
) {
    /**현재 드래그 중인 아이템 인덱스*/
    var draggingItemIndex by mutableStateOf<Int?>(null)
        private set
    /**스크롤 이벤트를 저장하기위한 채널 생성*/
    internal val scrollChannel = Channel<Float>()
    /**드래그 중인 아이템의 y 값*/
    private var draggingItemDraggedDelta by mutableStateOf(0f)
    /**드래그 중인 아이템 초기 옾셋*/
    private var draggingItemInitialOffset by mutableStateOf(0)
    /**현재 드래그 중인 아이템의 옾셋*/
    internal val draggingItemOffset: Float
        get() = draggingItemLayoutInfo?.let { item ->
            draggingItemInitialOffset + draggingItemDraggedDelta - item.offset
        } ?: 0f
    /**드래그 중인 아이템 레이아웃 정보 , swap 이벤트 정의를 위해 필요*/
    private val draggingItemLayoutInfo: LazyListItemInfo?
        get() = state.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == draggingItemIndex }
    /**직전에 드래그 했던 아이템의 인덱스*/
    internal var previousIndexOfDraggedItem by mutableStateOf<Int?>(null)
        private set
    /**직전 드래그 아이템 옾셋*/
    internal var previousItemOffset = Animatable(0f)
        private set
    /**드래그 시작 콜백*/
    internal fun onDragStart(offset: Offset) {
        //LazyList에서 보여지는 아이템의 정보를 불러옴 -> 선택한 아이템의 오프셋 범위 중 제일 1원소를 가져옴
        state.layoutInfo.visibleItemsInfo
            .firstOrNull { item ->
                offset.y.toInt() in item.offset..(item.offsetEnd)
            }?.also {
                //드래그 하는 아이템 인덱스
                draggingItemIndex = it.index
                //드래그 아이템 y축 시작 좌표값
                draggingItemInitialOffset = it.offset
            }
    }
    /**드래그가 중단되었을 때*/
    internal fun onDragInterrupted() {
        //드래그 중일 때
        if (draggingItemIndex != null) {
            //이전 인덱스에 현재 인덱스를 저장
            previousIndexOfDraggedItem = draggingItemIndex
            //시작 옾셋 = 현재 옾셋
            val startOffset = draggingItemOffset
            //코루틴 스코프
            scope.launch {
                //이전 아이템의 옾셋을 스냅샷 한것 처럼 저장
                previousItemOffset.snapTo(startOffset)
                //애니메이션 설정
                previousItemOffset.animateTo(
                    0f,
                    spring(
                        stiffness = Spring.StiffnessMediumLow,
                        visibilityThreshold = 1f
                    )
                )
                //이전에 선택한 아이템 null
                previousIndexOfDraggedItem = null
            }
        }
        draggingItemDraggedDelta = 0f
        draggingItemIndex = null
        draggingItemInitialOffset = 0
    }
    /**드래깅 콜백*/
    internal fun onDrag(offset: Offset) {
        //드래그한 Y값 -> 이동 값
        draggingItemDraggedDelta += offset.y
        //드래그중인 레이아웃 정보
        val draggingItem = draggingItemLayoutInfo ?: return
        //현재 드래그 중인 아이템 offset + Y축 이동 값
        val startOffset = draggingItem.offset + draggingItemOffset
        //시작지점 옾셋 + 사이즈
        val endOffset = startOffset + draggingItem.size
        //시작 옾셋 + size / 2
        val middleOffset = startOffset + (endOffset - startOffset) / 2f
        //치환할 대상 설정
        val targetItem = state.layoutInfo.visibleItemsInfo.find { item ->
            //가운데 옾셋이 해당 아이템의 옾셋 min, max범위 안이면서 현재 드래그 중인 인덱스와 같지 않을 때
            middleOffset.toInt() in item.offset..item.offsetEnd &&
                    draggingItem.index != item.index
        }
        //널 체크 및 indexOutOfBoundException 예외 방지
        if (targetItem != null) {
            //타깃 아이템이 보이는 아이템 중 제일 첫 번째 일때 scrollToIndex는 드래그 중인 인덱스로
            val scrollToIndex = if (targetItem.index == state.firstVisibleItemIndex) {
                draggingItem.index
                //드래그 중인 인덱스가 0번 인덱스 일 때 타깃 인덱스로
            } else if (draggingItem.index == state.firstVisibleItemIndex) {
                targetItem.index
            } else {
                null
            }
            if (scrollToIndex != null) {
                scope.launch {
                    // this is needed to neutralize automatic keeping the first item first.
                    //스크롤 위치를 즉시 스냅 (정지 시킴)
                    state.scrollToItem(scrollToIndex, state.firstVisibleItemScrollOffset)
                    //onMove 콜백 실행 -> 위치 변경
                    onMove.invoke(draggingItem.index, targetItem.index)
                }
            } else {
                //스크롤 중이지 않으면 그냥 위치 변경
                onMove.invoke(draggingItem.index, targetItem.index)
            }
            //현재 드래그 중인 아이템의 인덱스를 위치 바꾸는 인덱스로 저장
            draggingItemIndex = targetItem.index
        } else {
            val overscroll = when {
                //coerceAtLeast -> 패러미터로 넣은 minimumValue 아래로 넘어가지 않도록 강제함
                draggingItemDraggedDelta > 0 ->
                    (endOffset - state.layoutInfo.viewportEndOffset).coerceAtLeast(0f)

                draggingItemDraggedDelta < 0 ->
                    (startOffset - state.layoutInfo.viewportStartOffset).coerceAtMost(0f)

                else -> 0f
            }
            if (overscroll != 0f) {
                scrollChannel.trySend(overscroll)
            }
        }
    }
    /**offsetEnd를 리턴하는 확장함수*/
    private val LazyListItemInfo.offsetEnd: Int
        get() = this.offset + this.size
}
/**pointerInput을 리턴하는 Modifier의 확장함수*/
fun Modifier.dragContainer(dragDropState: DragDropState): Modifier {
    return pointerInput(dragDropState) {
        //꾹 누를 시
        detectDragGesturesAfterLongPress(
            onDrag = { change, offset ->
                change.consume()
                //dragDtopState의 onDrag 실행 <- 직접 구현한거
                dragDropState.onDrag(offset = offset)
            },
            onDragStart = { offset -> dragDropState.onDragStart(offset) },
            onDragEnd = { dragDropState.onDragInterrupted() },
            onDragCancel = { dragDropState.onDragInterrupted() }
        )
    }
}

@ExperimentalFoundationApi
@Composable
fun LazyItemScope.DraggableItem(
    dragDropState: DragDropState,
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(isDragging: Boolean) -> Unit
) {
    //드래그 중인 아이템
    val dragging = index == dragDropState.draggingItemIndex
    //그래픽 레이어를 통해 offset 위치 변경
    val draggingModifier = if (dragging) {
        Modifier
            .zIndex(1f)
            .graphicsLayer {
                translationY = dragDropState.draggingItemOffset
            }

    } else if (index == dragDropState.previousIndexOfDraggedItem) {
        Modifier
            .zIndex(1f)
            .graphicsLayer {
                translationY = dragDropState.previousItemOffset.value
            }
    } else {
        Modifier.animateItemPlacement()
    }
    Column(modifier = modifier.then(draggingModifier)) {
        content(dragging)
    }
}