package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 컨트롤러 슬라이스 — 서비스는 목. {@code /api/items/{id}} 와 경로가 겹치지 않는지 보려고 ItemController 도 함께 올린다. */
@WebMvcTest({ItemSearchController.class, ItemController.class})
@ActiveProfiles("test")
class ItemSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ItemSearchService itemSearchService;

    @MockBean
    private ItemService itemService;

    private ItemSearchRequest searchAndCaptureRequest(MockHttpServletRequestBuilder builder) throws Exception {
        when(itemSearchService.search(any())).thenReturn(new ItemSearchResponse(List.of(), 0, "검색 결과가 없습니다"));
        mockMvc.perform(builder).andExpect(status().isOk());
        ArgumentCaptor<ItemSearchRequest> captor = ArgumentCaptor.forClass(ItemSearchRequest.class);
        verify(itemSearchService).search(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("GET /api/items/search → 200, {rows, count, message} JSON, 행에는 정규화 필드만")
    void searchReturnsRowsCountMessage() throws Exception {
        when(itemSearchService.search(any())).thenReturn(new ItemSearchResponse(
            List.of(new ItemSearchRowResponse(12, "대분수의 덧셈", "M5-1", 3, List.of("계산", "오답률높음"))),
            1, null));

        mockMvc.perform(get("/api/items/search").param("q", "분수"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.count").value(1))
            .andExpect(jsonPath("$.message", nullValue()))
            .andExpect(jsonPath("$.status").doesNotExist())
            .andExpect(jsonPath("$.rows[0].id").value(12))
            .andExpect(jsonPath("$.rows[0].title").value("대분수의 덧셈"))
            .andExpect(jsonPath("$.rows[0].unit").value("M5-1"))
            .andExpect(jsonPath("$.rows[0].level").value(3))
            .andExpect(jsonPath("$.rows[0].tags[1]").value("오답률높음"))
            .andExpect(jsonPath("$.rows[0].stem").doesNotExist())
            .andExpect(jsonPath("$.rows[0].status").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/items/search 0건 → 404 가 아니라 200, 안내 문구")
    void searchNoMatchReturns200WithMessage() throws Exception {
        when(itemSearchService.search(any())).thenReturn(new ItemSearchResponse(List.of(), 0, "검색 결과가 없습니다"));

        mockMvc.perform(get("/api/items/search").param("unit", "X9-9"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rows.length()").value(0))
            .andExpect(jsonPath("$.count").value(0))
            .andExpect(jsonPath("$.message").value("검색 결과가 없습니다"));
    }

    @Test
    @DisplayName("GET /api/items/search 레거시 파라미터 이름을 원문 그대로 서비스에 넘긴다")
    void searchPassesLegacyParamsAsIs() throws Exception {
        ItemSearchRequest request = searchAndCaptureRequest(get("/api/items/search")
            .param("q", " 분수 ").param("unit", "m5-1").param("level", "3abc").param("tag", "문장제")
            .param("sort", "DROP").param("dir", "up").param("page", "-1"));

        assertThat(request).isEqualTo(new ItemSearchRequest(" 분수 ", "m5-1", "3abc", "문장제", "DROP", "up", "-1"));
    }

    @Test
    @DisplayName("GET /api/items/search 파라미터가 없으면 빈 값, page 만 1")
    void searchMissingParamsUsesLegacyDefaults() throws Exception {
        assertThat(searchAndCaptureRequest(get("/api/items/search")))
            .isEqualTo(new ItemSearchRequest("", "", "", "", "", "", "1"));
    }

    @Test
    @DisplayName("GET /api/items/search 빈 값 파라미터는 빈 문자열로 넘긴다(page= 포함)")
    void searchBlankParamsStayBlank() throws Exception {
        assertThat(searchAndCaptureRequest(get("/api/items/search?q=&unit=&level=&tag=&sort=&dir=&page=")))
            .isEqualTo(new ItemSearchRequest("", "", "", "", "", "", ""));
    }

    @Test
    @DisplayName("GET /api/items/search 같은 이름이 반복되면 마지막 값, q[] 배열이면 첫 값(PHP 해석)")
    void searchRepeatedParamsFollowPhp() throws Exception {
        ItemSearchRequest request = searchAndCaptureRequest(get("/api/items/search")
            .param("q", "a", "b").param("tag[]", "계산", "문장제"));

        assertThat(request.q()).isEqualTo("b");
        assertThat(request.tag()).isEqualTo("계산");
    }

    @Test
    @DisplayName("GET /api/items/{id} 는 검색 경로가 생겨도 그대로 문항 단건으로 간다")
    void getItemStillRoutesToItemController() throws Exception {
        when(itemService.getItem(3)).thenReturn(
            new ItemResponse(3, "소수 한 자리 수의 곱셈", "0.3 × 4", "M5-3", 1, "A", List.of("계산")));

        mockMvc.perform(get("/api/items/3"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(3));
    }
}
