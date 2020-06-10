package my.rs;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static my.rs.InjectionPsiUtil.EXPORT_ANNOTATION;
import static my.rs.InjectionPsiUtil.IMPLEMENTS_ANNOTATION;
import static my.rs.InjectionPsiUtil.IMPORT_ANNOTATION;
import static my.rs.InjectionPsiUtil.MIXINS_ANNOTATION;
import static my.rs.InjectionPsiUtil.MIXIN_ANNOTATION;
import static my.rs.InjectionPsiUtil.MIXIN_ANNOTATIONS;
import static my.rs.InjectionPsiUtil.RELEVANT_ANNOTATIONS;
import static my.rs.InjectionPsiUtil.findAnnotatedElements;
import static my.rs.InjectionPsiUtil.getAnnotationValue;
import static my.rs.InjectionPsiUtil.getMixinAnnotationValue;
import static my.rs.InjectionPsiUtil.isRelevantAnnotation;
import static my.rs.InjectionPsiUtil.isStatic;

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo;
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider;
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder;
import com.intellij.icons.AllIcons.ToolbarDecorator;
import com.intellij.ide.util.DefaultPsiElementCellRenderer;
import com.intellij.navigation.NavigationItem;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiJavaToken;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.presentation.java.SymbolPresentationUtil;
import com.intellij.psi.util.CachedValueProvider.Result;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.Icon;
import lombok.Value;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

public class InjectionAssistLineMarkerProvider extends RelatedItemLineMarkerProvider {

  private static final Logger log = Logger.getInstance(InjectionAssistLineMarkerProvider.class);

  /**
   * The mapping state associated with the current AST
   */
  @Value
  static class State {

    /**
     * Mapping of exported field name/location to additional info about them
     */
    Map<ExportedMember, ExportedMemberInfo> exports = new HashMap<>();

    /**
     * Mapping of references to exported fields
     */
    Map<PsiMember, Set<ExportedMember>> references = new HashMap<>();

    Map<String, PsiClass> implementers = new HashMap<>();

    void addExport(ExportedMember exportedMember, PsiMember member) {
      if (exportedMember == null) {
        return;
      }

      exports.computeIfAbsent(exportedMember, k -> new ExportedMemberInfo(member));
      references.computeIfAbsent(member, k -> new HashSet<>()).add(exportedMember);
    }

    void addReference(ExportedMember exportedMember, PsiMember reference) {
      if (exportedMember == null) {
        return;
      }

      ExportedMemberInfo info = exports.get(exportedMember);
      if (info == null) {
        return;
      }
      info.references.add(reference);
      references.computeIfAbsent(reference, k -> new HashSet<>()).add(exportedMember);
    }
  }

  /**
   * Information regarding a single exported field/method from runescape-client
   */
  @Value
  static class ExportedMemberInfo {

    PsiMember export;
    List<PsiMember> references = new ArrayList<>();
  }

  /**
   * A unique identifier (name+location) associated with an exported field/method from
   * runescape-client
   */
  @Value(staticConstructor = "of")
  static class ExportedMember {

    /**
     * Parses the Export annotation to determine the appropriate ExportedMember, using the value of
     * the containg class' Implements annotation for the location if non-static
     */
    public static ExportedMember fromExported(State state, PsiMember member) {
      String name = getAnnotationValue(member, EXPORT_ANNOTATION);
      if (name == null) {
        return null;
      }

      PsiClass containingClass = member.getContainingClass();
      if (containingClass == null) {
        return null;
      }

      // Export annotations always have a single reference (themselves)
      // If they're not static the location will be the value of the containg class' @Implements (if present)
      String rsApi = getAnnotationValue(containingClass, IMPLEMENTS_ANNOTATION);
      boolean isStatic = isStatic(member);
      if (rsApi == null && !isStatic) {
        return null;
      }

      if (rsApi != null) {
        state.implementers.put(rsApi, containingClass);
      }

      return ExportedMember.of(name, isStatic ? "<static>" : rsApi);
    }

    /**
     * Parses the Import annotation to determine the appropriate ExportedMember, using the name of
     * the containing class (with the RS prefix stripped) for the location if non-static
     */
    public static ExportedMember fromImported(State state, PsiMember member) {
      String name = getAnnotationValue(member, IMPORT_ANNOTATION);
      if (name == null) {
        return null;
      }

      PsiClass containingClass = member.getContainingClass();
      if (containingClass == null) {
        return null;
      }

      // Each import always references a single export, either an exported field in the same class
      // they themselves are located in, or otherwise a static field
      String rsApi = containingClass.getName();
      if (rsApi == null || !rsApi.startsWith("RS")) {
        return null;
      }

      rsApi = rsApi.substring(2);

      // First look for a non-static export
      ExportedMember exportedMember = ExportedMember.of(name, rsApi);
      if (!state.exports.containsKey(exportedMember)) {
        // If not found, then try static instead
        exportedMember = ExportedMember.of(name, "<static>");
      }

      return exportedMember;
    }

    /**
     * Parses the mixin annotations to determine the appropriate ExportedMembers. Mixins can have
     * multiple targets in case the Mixins (plural) annotation is used to apply the mixin to
     * multiple classes.
     * <p>
     * If the field is non-static the value of the Mixin annotation(s) (with the RS prefix stripped)
     * will be used for the location if non-static.
     */
    public static Collection<ExportedMember> fromMixin(State state, PsiMember member) {
      String name = null;

      for (String mixinAnnotation : MIXIN_ANNOTATIONS) {
        name = getAnnotationValue(member, mixinAnnotation);
        if (name != null) {
          break;
        }
      }

      if (name == null) {
        return emptyList();
      }

      PsiClass containingClass = member.getContainingClass();
      if (containingClass == null) {
        return emptyList();
      }

      // If it's a static field then we must reference a single static export
      if (isStatic(member)) {
        return singleton(ExportedMember.of(name, "<static>"));
      }

      List<PsiClassType> targetTypes = emptyList();
      PsiAnnotation mixinAnnotation;
      if ((mixinAnnotation = containingClass.getAnnotation(MIXIN_ANNOTATION)) != null) {
        // Parse the single targetType from the Mixin annotation
        PsiClassType targetType = getMixinAnnotationValue(mixinAnnotation);
        if (targetType != null) {
          targetTypes = singletonList(targetType);
        }

      } else if ((mixinAnnotation = containingClass.getAnnotation(MIXINS_ANNOTATION)) != null) {
        // Parse the list of targetTypes from the Mixins annotation, which contains multiple Mixin annotations
        PsiArrayInitializerMemberValue values = (PsiArrayInitializerMemberValue) mixinAnnotation
            .findAttributeValue("value");
        if (values != null) {
          PsiAnnotationMemberValue[] initializers = values.getInitializers();
          targetTypes = new ArrayList<>(initializers.length);
          for (PsiAnnotationMemberValue initializer : initializers) {
            if (initializer instanceof PsiAnnotation) {
              PsiClassType targetType = getMixinAnnotationValue((PsiAnnotation) initializer);
              if (targetType != null) {
                targetTypes.add(targetType);
              }
            }
          }
        }
      }

      Collection<ExportedMember> result = new HashSet<>(targetTypes.size());
      for (PsiClassType targetClass : targetTypes) {
        String rsApi = targetClass.getClassName();
        if (rsApi == null || !rsApi.startsWith("RS")) {
          continue;
        }

        // xyz
        rsApi = rsApi.substring(2);

        ExportedMember exportedMember = ExportedMember.of(name, rsApi);

        if ("<init>".equals(name)) {
          // Constructors are a special case that we should always consider to be exported
          PsiClass implementer = state.implementers.get(rsApi);
          if (implementer != null) {
            PsiMethod[] constructors = implementer.getConstructors();
            if (constructors.length == 1) {
              // We're lazy and only support target classes with a single constructor right now
              // TODO linking back also doesn't work, since we currently only place navigational hints on annotations
              state.addExport(exportedMember, constructors[0]);
            }
          }
        }

        if (state.exports.containsKey(exportedMember)) {
          result.add(exportedMember);
        }
      }

      return result;
    }

    String name;
    String location;
  }


  /**
   * Calculates the current state if not cached
   */
  private static State calculateState(
      Project project) {
    CachedValuesManager cacheManager = CachedValuesManager.getManager(project);
    return cacheManager.getCachedValue(project, () -> {
      State state = new State();

      // First just locate all the exports
      findAnnotatedElements(project, EXPORT_ANNOTATION, annotatedElement -> {
        PsiMember member = annotatedElement.getMember();
        state.addExport(ExportedMember.fromExported(state, member), member);
      });

      // Then process all of the imports
      findAnnotatedElements(project, IMPORT_ANNOTATION, annotatedElement -> {
        PsiMember member = annotatedElement.getMember();
        state.addReference(ExportedMember.fromImported(state, member), member);
      });

      // Finally process all the mixin annotations
      findAnnotatedElements(project, MIXIN_ANNOTATIONS, annotatedElement -> {
        PsiMember member = annotatedElement.getMember();
        for (ExportedMember exportedMember : ExportedMember.fromMixin(state, member)) {
          state.addReference(exportedMember, member);
        }
      });

      return Result.create(state, PsiModificationTracker.MODIFICATION_COUNT);
    });
  }

  @Override
  protected void collectNavigationMarkers(@NotNull PsiElement element,
      @NotNull Collection<? super RelatedItemLineMarkerInfo> result) {
    PsiElement parent;
    // Check if we're matching an annotation name
    if (!(element instanceof PsiJavaToken) || !((parent = element
        .getParent()) instanceof PsiAnnotation)) {
      return;
    }

    // And that it is a relevant one
    PsiAnnotation annotation = (PsiAnnotation) parent;
    if (!isRelevantAnnotation(annotation)) {
      return;
    }

    String qualifiedName = annotation.getQualifiedName();
    if (qualifiedName == null) {
      return;
    }

    // Traverse up the tree to get the wrapping PsiMember (TODO is there a better way?)
    if (!((parent = annotation.getParent()) instanceof PsiModifierList)) {
      return;
    }

    if (!((parent = parent.getParent()) instanceof PsiMember)) {
      return;
    }

    PsiMember member = (PsiMember) parent;
    Project project = element.getProject();

    // Now that we're processing a relevant entry, let's calculate the state (or get from cache)
    State state = calculateState(project);

    List<PsiMember> targets = emptyList();
    String navigationType;
    Icon icon;

    if (EXPORT_ANNOTATION.equals(qualifiedName)) {
      navigationType = "reference";
      icon = ToolbarDecorator.Import;

      ExportedMemberInfo exportInfo = state.exports.get(ExportedMember.fromExported(state, member));
      if (exportInfo != null) {
        targets = exportInfo.references;
      }

    } else {
      navigationType = "export";
      icon = ToolbarDecorator.Export;

      Set<ExportedMember> referencedExportedMembers = state.references
          .getOrDefault(member, emptySet());
      targets = new ArrayList<>(referencedExportedMembers.size());
      for (ExportedMember exportedMember : referencedExportedMembers) {
        ExportedMemberInfo info = state.exports.get(exportedMember);
        if (info != null) {
          targets.add(info.export);
        }
      }
    }

    if (targets.isEmpty()) {
      return;
    }

    // TODO more intelligent sorting (or does intellij do its own sorting?)
    // might be nice to always show annotation types in a certain order?
    targets.sort(Comparator.comparing(NavigationItem::getName));

    result.add(NavigationGutterIconBuilder.create(icon)
        .setTargets(targets)
        .setCellRenderer(new DefaultPsiElementCellRenderer() {
          @Override
          public String getContainerText(PsiElement element, final String name) {
            String prefix = "";
            if (element instanceof PsiMember) {
              // Prefix the type of the annotation, e.g. "Copy (in x.y.z)"
              PsiMember member = (PsiMember) element;
              for (String relevantAnnotation : RELEVANT_ANNOTATIONS) {
                PsiAnnotation annotation = member.getAnnotation(relevantAnnotation);
                if (annotation != null) {
                  prefix = StringUtils.substringAfterLast(relevantAnnotation, ".") + " ";
                  break;
                }
              }
            }

            return prefix + SymbolPresentationUtil.getSymbolContainerText(element);
          }
        })
        .setTooltipText(targets.size() == 1 ?
            String.format("Navigate to %s", navigationType) :
            String.format("Navigate to %d %ss", targets.size(), navigationType))
        .createLineMarkerInfo(element));

  }
}